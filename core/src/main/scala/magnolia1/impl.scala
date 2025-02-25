package magnolia1

import scala.compiletime.*
import scala.deriving.Mirror
import scala.reflect.*

import Macro.*

object CaseClassDerivation:
  inline def fromMirror[Typeclass[_], A](
      product: Mirror.ProductOf[A]
  ): CaseClass[Typeclass, A] =
    val params = IArray(
      paramsFromMaps[
        Typeclass,
        A,
        product.MirroredElemLabels,
        product.MirroredElemTypes
      ](
        paramAnns[A].to(Map),
        inheritedParamAnns[A].to(Map),
        paramTypeAnns[A].to(Map),
        repeated[A].to(Map),
        defaultValue[A].to(Map)
      )*
    )
    ProductCaseClass(
      typeInfo[A],
      isObject[A],
      isValueClass[A],
      params,
      IArray(anns[A]*),
      IArray(inheritedAnns[A]*),
      IArray[Any](typeAnns[A]*),
      product
    )

  class ProductCaseClass[Typeclass[_], A](
      typeInfo: TypeInfo,
      isObject: Boolean,
      isValueClass: Boolean,
      parameters: IArray[CaseClass.Param[Typeclass, A]],
      annotations: IArray[Any],
      inheritedAnnotations: IArray[Any],
      typeAnnotations: IArray[Any],
      product: Mirror.ProductOf[A]
  ) extends CaseClass[Typeclass, A](
        typeInfo,
        isObject,
        isValueClass,
        parameters,
        annotations,
        inheritedAnnotations,
        typeAnnotations
      ):
    def construct[PType: ClassTag](makeParam: Param => PType): A =
      product.fromProduct(Tuple.fromArray(parameters.map(makeParam).to(Array)))

    def rawConstruct(fieldValues: Seq[Any]): A =
      product.fromProduct(Tuple.fromArray(fieldValues.to(Array)))

    def constructEither[Err, PType: ClassTag](
        makeParam: Param => Either[Err, PType]
    ): Either[List[Err], A] =
      parameters
        .map(makeParam)
        .foldLeft[Either[List[Err], Array[PType]]](Right(Array())) {
          case (Left(errs), Left(err))    => Left(errs ++ List(err))
          case (Right(acc), Right(param)) => Right(acc ++ Array(param))
          case (errs @ Left(_), _)        => errs
          case (_, Left(err))             => Left(List(err))
        }
        .map { params => product.fromProduct(Tuple.fromArray(params)) }

    def constructMonadic[M[_]: Monadic, PType: ClassTag](
        makeParam: Param => M[PType]
    ): M[A] = {
      val m = summon[Monadic[M]]
      m.map {
        parameters.map(makeParam).foldLeft(m.point(Array())) { (accM, paramM) =>
          m.flatMap(accM) { acc =>
            m.map(paramM)(acc ++ List(_))
          }
        }
      } { params => product.fromProduct(Tuple.fromArray(params)) }
    }

  inline def paramsFromMaps[Typeclass[_], A, Labels <: Tuple, Params <: Tuple](
      annotations: Map[String, List[Any]],
      inheritedAnnotations: Map[String, List[Any]],
      typeAnnotations: Map[String, List[Any]],
      repeated: Map[String, Boolean],
      defaults: Map[String, Option[() => Any]],
      idx: Int = 0
  ): List[CaseClass.Param[Typeclass, A]] =
    inline erasedValue[(Labels, Params)] match
      case _: (EmptyTuple, EmptyTuple) =>
        Nil
      case _: ((l *: ltail), (p *: ptail)) =>
        def unsafeCast(any: Any) = Option.when(any == null || (any: @unchecked).isInstanceOf[p])(any.asInstanceOf[p])
        val label = constValue[l].asInstanceOf[String]
        paramFromMaps[Typeclass, A, p](
          label,
          CallByNeed(summonInline[Typeclass[p]]),
          CallByNeed.withValueEvaluator(defaults.get(label).flatten.flatMap(d => unsafeCast(d.apply))),
          repeated,
          annotations,
          inheritedAnnotations,
          typeAnnotations,
          idx
        ) ::
          paramsFromMaps[Typeclass, A, ltail, ptail](
            annotations,
            inheritedAnnotations,
            typeAnnotations,
            repeated,
            defaults,
            idx + 1
          )

  private def paramFromMaps[Typeclass[_], A, p](
      label: String,
      tc: CallByNeed[Typeclass[p]],
      d: CallByNeed[Option[p]],
      repeated: Map[String, Boolean],
      annotations: Map[String, List[Any]],
      inheritedAnnotations: Map[String, List[Any]],
      typeAnnotations: Map[String, List[Any]],
      idx: Int
  ): CaseClass.Param[Typeclass, A] =
    CaseClass.Param[Typeclass, A, p](
      label,
      idx,
      repeated.getOrElse(label, false),
      tc,
      d,
      IArray.from(annotations.getOrElse(label, List())),
      IArray.from(inheritedAnnotations.getOrElse(label, List())),
      IArray.from(typeAnnotations.getOrElse(label, List()))
    )

end CaseClassDerivation

trait SealedTraitDerivation:
  type Typeclass[T]

  protected inline def deriveSubtype[s](m: Mirror.Of[s]): Typeclass[s]

  protected inline def sealedTraitFromMirror[A](
      m: Mirror.SumOf[A]
  ): SealedTrait[Typeclass, A] =
    SealedTrait(
      typeInfo[A],
      IArray(subtypesFromMirror[A, m.MirroredElemTypes](m)*),
      IArray.from(anns[A]),
      IArray(paramTypeAnns[A]*),
      isEnum[A],
      IArray.from(inheritedAnns[A])
    )

  protected transparent inline def subtypesFromMirror[A, SubtypeTuple <: Tuple](
      m: Mirror.SumOf[A],
      idx: Int = 0 // no longer used, kept for bincompat
  ): List[SealedTrait.Subtype[Typeclass, A, _]] =
    inline erasedValue[SubtypeTuple] match
      case _: EmptyTuple =>
        Nil
      case _: (s *: tail) =>
        val sub = summonFrom {
          case mm: Mirror.SumOf[`s`] =>
            subtypesFromMirror[A, mm.MirroredElemTypes](
              mm.asInstanceOf[m.type],
              0
            )
          case _ =>
            List(
              new SealedTrait.Subtype[Typeclass, A, s](
                typeInfo[s],
                IArray.from(anns[s]),
                IArray.from(inheritedAnns[s]),
                IArray.from(paramTypeAnns[A]),
                isObject[s],
                idx,
                CallByNeed(summonFrom {
                  case tc: Typeclass[`s`] => tc
                  case _                  => deriveSubtype(summonInline[Mirror.Of[s]])
                }),
                x => x.isInstanceOf[s & A],
                _.asInstanceOf[s & A]
              )
            )
        }
        (sub ::: subtypesFromMirror[A, tail](m, idx + 1)).distinctBy(_.typeInfo).sortBy(_.typeInfo.full)
end SealedTraitDerivation
