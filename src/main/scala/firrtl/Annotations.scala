package firrtl

import scala.collection.mutable
import java.io.Writer


/**
 * Firrtl Annotation Library
 *
 * WARNING(izraelevitz): Untested, and requires unit tests, which require the
 * LowerTypes pass and ConstProp pass to correctly populate its RenameMap.
 *
 * The following tables explain how Tenacity and Permissibility interact
 * with different RenameMap scenarios:
 *  x -> x   : a component named "x" is renamed to the same name, "x"
 *  x -> y   : a component named "x" is renamed to a different name, "y"
 *  x -> y,z : a component named "x" is split into two components named "y" and "z"
 *  x -> ()  : a component named "x" is removed
 *
 * Tenacity Propagation Behavior:
 * ----------|----------|----------|------------|-----------|
 *           |  x -> x  |  x -> y  |  x -> y,z  |  x -> ()  |
 * ----------|----------|----------|------------|-----------|
 * Unstable  |    x     |    ()    |     ()     |    ()     |
 * Fickle    |    x     |    y     |     ()     |    ()     |
 * Insistent |    x     |    y     |     y      |    ()     |
 * Sticky    |    x     |    y     |     y,z    |    ()     |
 * ----------|----------|----------|------------|-----------|
 *
 * Permissibility Accepted Ranges:
 * ----------|----------|----------|------------|-----------|
 *           |  x -> x  |  x -> y  |  x -> y,z  |  x -> ()  |
 * ----------|----------|----------|------------|-----------|
 * Strict    |   ok     |   error  |    error   |   error   |
 * Rigid     |   ok     |    ok    |    error   |   error   |
 * Firm      |   ok     |    ok    |     ok     |   error   |
 * Loose     |   ok     |    ok    |     ok     |    ok     |
 * ----------|----------|----------|------------|-----------|
 */
object Annotations {

  case class AnnotationException(message: String) extends Exception(message)

  /**
   * Named classes associate an annotation with a component in a Firrtl circuit
   */
  trait Named { def name: String }
  case class CircuitName(name: String) extends Named
  case class ModuleName(name: String, circuit: CircuitName) extends Named
  case class ComponentName(name: String, module: ModuleName) extends Named

  /**
   * Transform ID (TransID) associates an annotation with an instantiated
   * Firrtl compiler transform
   */
  case class TransID(id: Int)

  /**
   * Permissibility defines the range of acceptable changes to the annotated component.
   */
  trait Permissibility {
    def check(from: Named, tos: Seq[Named], which: Annotation): Unit
  }
  /**
   * Annotated component cannot be renamed, expanded, or removed.
   */
  trait Strict extends Permissibility {
    def check(from: Named, tos: Seq[Named], which: Annotation): Unit = tos.size match {
      case 0 =>
        throw new AnnotationException(s"Cannot remove the strict annotation ${which.serialize} on ${from.name}")
      case 1 if(from != tos.head) =>
        throw new AnnotationException(s"Cannot rename the strict annotation ${which.serialize} on ${from.name} -> ${tos.head.name}")
      case _ =>
        throw new AnnotationException(s"Cannot expand a strict annotation on ${from.name} -> ${tos.map(_.name)}")
    }
  }

  /**
   * Annotated component can be renamed, but cannot be expanded or removed.
   */
  trait Rigid extends Permissibility {
    def check(from: Named, tos: Seq[Named], which: Annotation): Unit = tos.size match {
      case 0 => throw new AnnotationException(s"Cannot remove the rigid annotation ${which.serialize} on ${from.name}")
      case 1 => {}
      case _ => throw new AnnotationException(s"Cannot expand a rigid annotation on ${from.name} -> ${tos.map(_.name)}")
    }
  }

  /**
   * Annotated component can be renamed, and expanded, but not removed.
   */
  trait Firm extends Permissibility {
    def check(from: Named, tos: Seq[Named], which: Annotation): Unit = tos.size match {
      case 0 => throw new AnnotationException(s"Cannot remove the firm annotation ${which.serialize} on ${from.name}")
      case _ => {}
    }
  }

  /**
   * Annotated component can be renamed, and expanded, and removed.
   */
  trait Loose extends Permissibility {
    def check(from: Named, tos: Seq[Named], which: Annotation): Unit = {}
  }

  /**
   * Tenacity defines how the annotation propagates when changes to the
   * annotated component occur.
   */
  trait Tenacity {
    protected def propagate(from: Named, tos: Seq[Named], dup: Named=>Annotation): Seq[Annotation]
  }

  /**
   * Annotation propagates to all new components
   */
  trait Sticky extends Tenacity {
    protected def propagate(from: Named, tos: Seq[Named], dup: Named=>Annotation): Seq[Annotation] = tos.map(dup(_))
  }

  /**
   * Annotation propagates to the first of all new components
   */
  trait Insistent extends Tenacity {
    protected def propagate(from: Named, tos: Seq[Named], dup: Named=>Annotation): Seq[Annotation] = tos.headOption match {
      case None => Seq.empty
      case Some(n) => Seq(dup(n))
    }
  }

  /**
   * Annotation propagates only if there is one new component.
   */
  trait Fickle extends Tenacity {
    protected def propagate(from: Named, tos: Seq[Named], dup: Named=>Annotation): Seq[Annotation] = tos.size match {
      case 1 => Seq(dup(tos.head))
      case _ => Seq.empty
    }
  }

  /**
   * Annotation propagates only the new component shares the same name.
   */
  trait Unstable extends Tenacity {
    protected def propagate(from: Named, tos: Seq[Named], dup: Named=>Annotation): Seq[Annotation] = tos.size match {
      case 1 if(tos.head == from) => Seq(dup(tos.head))
      case _ => Seq.empty
    }
  }

  /**
   * Annotation associates with a given named circuit component (target) and a
   * given transformation (tID).  Also defined are the legal ranges of changes
   * to the associated component (Permissibility) and how the annotation
   * propagates under such changes (Tenacity). Subclasses must implement the
   * duplicate function to create the same annotation associated with a new
   * component.
   */
  trait Annotation extends Permissibility with Tenacity {
    def target: Named
    def tID: TransID
    protected def duplicate(n: Named): Annotation
    def serialize: String = this.toString
    def update(tos: Seq[Named]): Seq[Annotation] = {
      check(target, tos, this)
      propagate(target, tos, duplicate _)
    }
  }

  /**
   * Container of all annotations for a Firrtl compiler.
   */
  case class AnnotationMap(annotations: Seq[Annotation]) {
    type NamedMap = Map[Named, Map[TransID, Annotation]]
    type IDMap = Map[TransID, Map[Named, Annotation]]

    val (namedMap: NamedMap, idMap:IDMap) =
      //annotations.foldLeft(Tuple2[NamedMap, IDMap](Map.empty, Map.empty)){
      annotations.foldLeft((Map.empty: NamedMap, Map.empty: IDMap)){
        (partialMaps: Tuple2[NamedMap, IDMap], annotation: Annotation) => {
          val tIDToAnn = partialMaps._1.getOrElse(annotation.target, Map.empty)
          val pNMap = partialMaps._1 + (annotation.target -> (tIDToAnn + (annotation.tID -> annotation)))

          val nToAnn = partialMaps._2.getOrElse(annotation.tID, Map.empty)
          val ptIDMap = partialMaps._2 + (annotation.tID -> (nToAnn + (annotation.target -> annotation)))
          Tuple2(pNMap, ptIDMap)
        }
      }
    def get(id: TransID): Option[Map[Named, Annotation]] = idMap.get(id)
    def get(named: Named): Option[Map[TransID, Annotation]] = namedMap.get(named)
  }
}

