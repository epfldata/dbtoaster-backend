package ddbt.lib

import akka.actor.{Actor,ActorRef,ActorSystem,Props}
import akka.remote.RemoteScope
import scala.reflect.ClassTag
import java.io.InputStream

object Helper {
  import Messages._

  // ---------------------------------------------------------------------------
  // Akka helpers
  def actorSys(name:String="DDBT",host:String=null,port:Int=0) = {
    val conf = "akka.loglevel=ERROR\nakka.log-dead-letters-during-shutdown=off\n"+ // disable verbose logging
               MessageSerializer.conf()+ // custom serializer
               (if (host!=null) "akka {\nactor.provider=\"akka.remote.RemoteActorRefProvider\"\nremote {\n"+
                "enabled-transports=[\"akka.remote.netty.tcp\"]\nnetty.tcp {\nhostname=\""+host+"\"\nport="+port+"\n}\n"+"}\n}\n" else "")
    val user = { val f="conf/akka.conf"; if (new java.io.File(f).exists) scala.io.Source.fromFile(f).mkString else "" }
    val system = ActorSystem(name, com.typesafe.config.ConfigFactory.parseString(conf+user))
    //Runtime.getRuntime.addShutdownHook(new Thread{ override def run() = { /*println("Stopping "+host+":"+port);*/ system.shutdown() } });
    /*println("Started "+host+":"+port);*/ system
  }

  // ---------------------------------------------------------------------------
  // Run query actor and collect time + resulting maps or values (for 0-key maps)
  // The result is usually like List(Map[K1,V1],Map[K2,V2],Value3,Map...)
  private type Streams = Seq[(InputStream,Adaptor,Split)]
  @inline private def askWait[T](actor:ActorRef,msg:Any,timeout:Long=0L) = {
    val to=akka.util.Timeout(if (timeout<=0) (1L<<42) /*139 years*/ else timeout+2000)
    scala.concurrent.Await.result(akka.pattern.ask(actor,msg)(to), to.duration).asInstanceOf[T]
  }
  def mux(actor:ActorRef,streams:Streams,parallel:Boolean=true,timeout:Long=0) = {
    val mux = SourceMux(streams.map {case (in,ad,sp) => (in,Decoder((ev:TupleEvent)=>{ actor ! ev },ad,sp))},parallel)
    actor ! StreamInit(timeout); mux.read(); askWait[(StreamStat,List[Any])](actor,EndOfStream,timeout)
  }

  def run[Q<:akka.actor.Actor](streams:Streams,parallel:Boolean=true,timeout:Long=0)(implicit cq:ClassTag[Q]) = {
    val system = actorSys()
    val query = system.actorOf(Props[Q],"Query")
    try { mux(query,streams,parallel,timeout); } finally { system.shutdown }
  }

  // ---------------------------------------------------------------------------
  // Akka helper, reuse the same nodes across samples. Uses additional arguments to configure:
  // 0|1: -H<host:port> -W<workers> -C<cluster_mode>:<hosts_num>  [debug]
  // 2|3: -H<host:port> -W<workers> -M<master_host>:<master_port> [worker]
  //      -H<host:port> -W<total_expected_workers>                [master]
  private var runMaster:ActorRef = null
  private var runCount = 0
  def runLocal[M<:akka.actor.Actor,W<:akka.actor.Actor](args:Array[String])(streams:Streams,parallel:Boolean=true,timeout:Long=0)(implicit cm:ClassTag[M],cw:ClassTag[W]) : (StreamStat,List[Any]) = {
    def ad[T](s:String,d:T,f:Array[String]=>T) = args.filter(_.startsWith(s)).lastOption.map(x=>f(x.substring(s.length).split(":"))).getOrElse(d)
    val master:ActorRef = if (runCount>0 && runMaster!=null) { runCount-=1; runMaster }
    else { runCount=ad("-n",0,x=>math.max(0,x(0).toInt-1))
      val (debug,hosts_num)=ad("-C",(ad("-H",0,x=>2),1),x=>(x(0).toInt,x(1).toInt))
      val (host,port)=ad("-H",("127.0.0.1",8800),x=>(x(0),x(1).toInt))
      val wnum = ad("-W",1,x=>x(0).toInt)
      val isMaster = debug <= 1 || ad("-M",true,x=>false)
      val system = if (debug==0) actorSys() else actorSys(host=host,port=port)
      val workers:Seq[ActorRef] = debug match {
        case 0 => (0 until hosts_num * wnum).map(i=>system.actorOf(Props[W]())) // launch all in one system
        case 1 => (0 until hosts_num).flatMap { h=> val s=actorSys("DDBT"+h,host,port+1+h); (0 until wnum).map(i=>s.actorOf(Props[W]())) } // launch one system for each node group
        case _ => if (isMaster) Seq() else (0 until wnum).map(i=>system.actorOf(Props[W]()))
      }
      val master = if (isMaster) system.actorOf(Props[M](),name="master") else null
      debug match {
        case 0|1 => master ! Members(master,workers.toArray)
        case _ if isMaster =>askWait[Any](system.actorOf(Props(classOf[HelperActor],master,wnum),name="helper"),"ready")
        case _ => val (h,p)=ad("-M",(host,port),x=>(x(0),x(1).toInt)); system.actorSelection("akka.tcp://DDBT@"+h+":"+p+"/user/helper") ! workers.toArray
          println(wnum+" workers started"); system.awaitTermination; println("Shutdown"); System.exit(0)
      }
      class HelperActor(master:ActorRef,waiting:Int) extends Actor {
        private var workers = new Array[ActorRef](0)      // Collects all workers advertisements and forward them to the master.
        private var watcher = null.asInstanceOf[ActorRef] // Responds to "ready" when all workers are available.
        def receive = {
          case "ready" => watcher=sender; if (workers.size==waiting) watcher ! ()
          case as:Array[ActorRef] => workers++=as; if (workers.size==waiting) { master ! Members(master,workers.toArray); if (watcher!=null) watcher ! () }
        }
      }
      //try mux(master,streams,parallel,timeout) finally { master ! ClusterShutdown; Thread.sleep(100); System.gc; Thread.sleep(100) }
      master
    }
    try mux(master,streams,parallel,timeout) finally {
      if (runCount>0) { runMaster=master; master ! Reset } else { runMaster=null; master ! Shutdown }
      Thread.sleep(100); System.gc; Thread.sleep(100)
    }
  }

  // ---------------------------------------------------------------------------
  // Query benchmark, supported arguments:
  //   -n<num>       number of samples (default=1)
  //   -d<set>       dataset selection (can be repeated), (default=standard)
  //   -t<num>       set execution timeout (in miliseconds)
  //   -m<num>       0=hide output (verification mode), 1=sampling (benchmark mode)
  //   -p            use parallel input streams
  def bench(args:Array[String],run:(String,Boolean,Long)=>(StreamStat,List[Any]),op:List[Any]=>Unit=null) {
    def ad[T](s:String,d:T,f:String=>T) = args.filter(_.startsWith(s)).lastOption.map(x=>f(x.substring(s.length))).getOrElse(d)
    val num = ad("-n",1,x=>math.max(0,x.toInt))
    val mode = ad("-m",-1,x=>x.toInt)
    val timeout = ad("-t",0L,x=>x.toLong)
    val parallel = ad("-p",false,x=>true)
    var ds = args.filter(x=>x.startsWith("-d")).map(x=>x.substring(2)); if (ds.size==0) ds=Array("standard")
    if (mode<0) println("Java "+System.getProperty("java.version")+", Scala "+util.Properties.versionString.replaceAll(".* ",""))
    ds.foreach { d=> var i=0; var res0:List[Any]=null
      while (i<num) { i+=1;
        val (t,res)=run(d,parallel,timeout); if (t.skip==0) { if (res0==null) res0=res else assert(res0==res,"Inconsistent results: "+res0+" != "+res); }
        if (mode==1) println("SAMPLE="+d+","+(t.ns/1000)+","+t.count+","+t.skip)
        if (mode<0) println("Time: "+t)
      }
      if (mode!=1 && res0!=null && op!=null) op(res0)
    }
  }

  // ---------------------------------------------------------------------------
  // Correctness helpers

  val precision = 7 // significative numbers (7 to pass r_sumdivgrp, 10 otherwise)
  private val diff_p = Math.pow(0.1,precision)
  private def eq_v[V](v1:V,v2:V) = v1==v2 || ((v1,v2) match { case (d1:Double,d2:Double) => (Math.abs(2*(d1-d2)/(d1+d2))<diff_p) case _ => false })
  private def eq_p(p1:Product,p2:Product) = { val n=p1.productArity; assert(n==p2.productArity); var r=true; for (i <- 0 until n) { r = r && eq_v(p1.productElement(i),p2.productElement(i)) }; r }

  def diff[V](v1:V,v2:V) = if (!eq_v(v1,v2)) throw new Exception("Bad value: "+v1+" (expected "+v2+")")
  def diff[K,V](map1:Map[K,V],map2:Map[K,V]) = { // map1 is the test result, map2 is the reference
    val m1 = map1.filter{ case (k,v) => map2.get(k) match { case Some(v2) => v2!=v case None => true } }
    val m2 = map2.filter{ case (k,v) => map1.get(k) match { case Some(v2) => v2!=v case None => true } }
    if (m1.size>0 || m2.size>0) {
      val err=new StringBuilder()
      val b1 = scala.collection.mutable.HashMap[K,V](); b1 ++= m1
      val b2 = scala.collection.mutable.HashMap[K,V](); b2 ++= m2
      m1.foreach { x=> x._2 match { case d1:Double => if (Math.abs(d1)<diff_p) b1.remove(x._1) case _ => }} // ignore 'almost zero' values
      m1.foreach { case (k1,v1) =>
        m2.foreach { case (k2,v2) =>
          if (b1.contains(k1) && b2.contains(k2)) {
            val (k,v) = ((k1,k2) match { case (p1:Product,p2:Product) => eq_p(p1,p2) case _ => eq_v(k1,k2) }, eq_v(v1,v2))
            if (k) { b1.remove(k1); b2.remove(k2); if (!v) err.append("Bad value: "+k1+" -> "+v1+" (expected "+v2+")\n") }
          }
        }
      }
      b1.foreach { case (k,v) => err.append("Extra key: "+k+" -> "+v+"\n") }
      b2.foreach { case (k,v) => err.append("Missing key: "+k+" -> "+v+"\n") }
      val s = err.toString; if (s!="") { val e=new Exception("Result differs:\n"+s); e.setStackTrace(Array[StackTraceElement]()); throw e }
    }
  }

  def loadCSV[K,V](kv:List[Any]=>(K,V),file:String,fmt:String,sep:String=","):Map[K,V] = {
    val m = new java.util.HashMap[K,V]()
    def f(e:TupleEvent) = { val (k,v)=kv(e.data); m.put(k,v) }
    val d = Decoder(f,new Adaptor.CSV("REF",fmt,sep),Split())
    val s = SourceMux(Seq((new java.io.FileInputStream(file),d)))
    s.read; scala.collection.JavaConversions.mapAsScalaMap(m).toMap
  }
}
