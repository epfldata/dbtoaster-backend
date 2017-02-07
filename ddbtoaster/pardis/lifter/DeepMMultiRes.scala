/* Generated by Purgatory 2014-2017 */

package ddbt.lib.store.deep

import ch.epfl.data.sc.pardis
import pardis.ir._
import pardis.types.PardisTypeImplicits._
import pardis.effects._
import pardis.deep._
import pardis.deep.scalalib._
import pardis.deep.scalalib.collection._
import pardis.deep.scalalib.io._

trait MultiResOps extends Base  {  
  // Type representation
  val MultiResType = MultiResIRs.MultiResType
  implicit val typeMultiRes: TypeRep[MultiRes] = MultiResType
  implicit class MultiResRep(self : Rep[MultiRes]) {
     def isEmpty : Rep[Boolean] = multiResIsEmpty(self)
  }
  object MultiRes {

  }
  // constructors
   def __newMultiRes() : Rep[MultiRes] = multiResNew()
  // IR defs
  val MultiResNew = MultiResIRs.MultiResNew
  type MultiResNew = MultiResIRs.MultiResNew
  val MultiResIsEmpty = MultiResIRs.MultiResIsEmpty
  type MultiResIsEmpty = MultiResIRs.MultiResIsEmpty
  // method definitions
   def multiResNew() : Rep[MultiRes] = MultiResNew()
   def multiResIsEmpty(self : Rep[MultiRes]) : Rep[Boolean] = MultiResIsEmpty(self)
  type MultiRes = ddbt.lib.store.MultiRes
}
object MultiResIRs extends Base {
  // Type representation
  case object MultiResType extends TypeRep[MultiRes] {
    def rebuild(newArguments: TypeRep[_]*): TypeRep[_] = MultiResType
    val name = "MultiRes"
    val typeArguments = Nil
  }
      implicit val typeMultiRes: TypeRep[MultiRes] = MultiResType
  // case classes
  case class MultiResNew() extends ConstructorDef[MultiRes](List(), "MultiRes", List(List())){
    override def curriedConstructor = (x: Any) => copy()
  }

  case class MultiResIsEmpty(self : Rep[MultiRes]) extends FunctionDef[Boolean](Some(self), "isEmpty", List()){
    override def curriedConstructor = (copy _)
    override def isPure = true

    override def partiallyEvaluate(children: Any*): Boolean = {
      val self = children(0).asInstanceOf[MultiRes]
      self.isEmpty
    }
    override def partiallyEvaluable: Boolean = true

  }

  type MultiRes = ddbt.lib.store.MultiRes
}
trait MultiResImplicits extends MultiResOps { 
  // Add implicit conversions here!
}
trait MultiResComponent extends MultiResOps with MultiResImplicits {  }

trait MultiResPartialEvaluation extends MultiResComponent with BasePartialEvaluation {  
  // Immutable field inlining 

  // Mutable field inlining 
  // Pure function partial evaluation
}


