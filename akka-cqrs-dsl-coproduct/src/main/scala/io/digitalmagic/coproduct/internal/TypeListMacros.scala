package io.digitalmagic.coproduct
package internal

import scala.annotation.tailrec
import scala.collection.immutable.Map
import scala.reflect.macros.whitebox.Context

private[coproduct]
final class TypeListMacros(val c: Context) {
  import c.universe._

  val basePackage: Tree = q"_root_.io.digitalmagic.coproduct"

  def parseType(ConsSym: Symbol, NilSym: Symbol)(typ: Type): Either[String, List[Type]] = {
    @tailrec def loop(typ: Type, agg: List[Type]): Either[String, List[Type]] = typ.dealias match {
      case TypeRef(_, sym, args) => sym.asType.toType.dealias.typeSymbol match {
        case ConsSym => loop(args(1), args(0) :: agg)
        case NilSym => Right(agg)
        case sym => Left(s"Unexpected symbol $sym for type $typ: ${showRaw(typ)}")
      }
      case ExistentialType(_, res) => loop(res, agg)
      case _ => Left(s"Unable to parse type $typ: ${showRaw(typ)}")
    }
    loop(typ, List.empty).map(_.reverse)
  }

  @volatile var cache: Map[Any, Any] = Map.empty

  def memoize[A, B](a: A, f: A => B): B = cache.get(a) match {
    case Some(b: B@unchecked) => b
    case _ =>
      val b = f(a)
      cache += ((a, b))
      b
  }

  def memoizedTListTypes(tpe: Type): Either[String, List[Type]] =
    memoize(tpe, parseType(symbolOf[TCons[Nothing, Nothing]], symbolOf[TNil]))

  def memoizedTListKTypes(tpe: Type): Either[String, List[Type]] =
    memoize(tpe, parseType(symbolOf[TConsK[Nothing, Nothing]], symbolOf[TNilK]))

  def foldAbort[T](either: => Either[String, Tree]): c.Expr[T] =
    either.fold(c.abort(c.enclosingPosition, _), c.Expr[T])

  def materializeTListPos[A, L <: TList](implicit evA: c.WeakTypeTag[A], evL: c.WeakTypeTag[L]): c.Expr[TList.Pos[A, L]] = {
    val A = evA.tpe
    val L = evL.tpe
    foldAbort(for {
      algebras <- memoizedTListTypes(L)
      index    <- Right(algebras.indexWhere(_ =:= A)).filterOrElse(_ >= 0, s"$A is not a member of $L")
    } yield q"new $basePackage.TList.Pos[$A, $L]{ override val index: _root_.scala.Int = $index }")
  }

  def materializeTListKPos[F[_], L <: TListK](implicit evF: c.WeakTypeTag[F[_]], evL: c.WeakTypeTag[L]): c.Expr[TListK.Pos[F, L]] = {
    val F = evF.tpe
    val L = evL.tpe
    foldAbort(for {
      algebras <- memoizedTListKTypes(L)
      index    <- Right(algebras.indexWhere(_ =:= F)).filterOrElse(_ >= 0, s"$F is not a member of $L")
    } yield q"new $basePackage.TListK.Pos[$F, $L]{ override val index: _root_.scala.Int = $index }")
  }
}