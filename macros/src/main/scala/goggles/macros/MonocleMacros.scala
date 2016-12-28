package goggles.macros

import goggles.macros.OpticType._

import scala.reflect.macros.whitebox
import monocle._


object MonocleMacros {

  import AST._

  def getImpl(c: whitebox.Context)(args: c.Expr[Any]*): c.Tree = {
    import c.universe._
    import AST._

    type Interpret[A] = Parse[c.Type, c.Expr[Any], A]

    def getterExpression(tree: c.Tree): Interpret[c.Tree] = {
      for {
        info <- Parse.getLastParseInfo[c.Type, c.Expr[Any]](GetNotAllowed(show(tree)))
        verb <- Parse.fromOption(info.compositeOpticType.getVerb, GetNotAllowed(show(tree)))
      } yield q"($tree).${TermName(verb)}(())"
    }

    val errorOrAst: Either[ParseError, AppliedLens] =
      Parser.parseAppliedLens(Lexer(getContextStringParts(c)))

    val finalTree =
      for {
        ast <- Parse.fromEither(errorOrAst)
        tree <- interpretComposedLens(c)(ast.lens, AccessMode.Get, applied = true)
        getter <- getterExpression(tree)
      } yield getter

    val (errorOrTree, infos) = finalTree.eval(args.toList)
    infos.map(_.pretty).foreach(println)
    errorOrTree match {
      case Left(err) => c.abort(c.enclosingPosition, err.toString)
      case Right(tree) => tree
    }
  }

  def setImpl(c: whitebox.Context)(args: c.Expr[Any]*): c.Tree = {
    import c.universe._
    import AST._

    type Interpret[A] = Parse[c.Type, c.Expr[Any], A]

    def setterExpression(tree: c.Tree): Interpret[c.Tree] = {
      for {
        info <- Parse.getLastParseInfo[c.Type, c.Expr[Any]](SetNotAllowed(show(tree)))
      } yield info.compositeOpticType match {
        case OpticType.SetterType => tree
        case x => q"($tree).asSetter"
      }
    }

    val errorOrAst: Either[ParseError, AppliedLens] =
      Parser.parseAppliedLens(Lexer(getContextStringParts(c)))

    val finalTree: Interpret[c.Tree] =
      for {
        ast <- Parse.fromEither(errorOrAst)
        tree <- interpretComposedLens(c)(ast.lens, AccessMode.Set, applied = true)
        setter <- setterExpression(tree)
      } yield q"(new _root_.goggles.macros.MonocleModifyOps($setter))"

    val (errorOrTree, infos) = finalTree.eval(args.toList)
    infos.map(_.pretty).foreach(println)
    errorOrTree match {
      case Left(err) => c.abort(c.enclosingPosition, err.toString)
      case Right(tree) => tree
    }
  }

  def lensImpl(c: whitebox.Context)(args: c.Expr[Any]*): c.Tree = {
    import c.universe._

    type Interpret[A] = Parse[c.Type, c.Expr[Any], A]

    val errorOrAst: Either[ParseError, ComposedLens] =
      Parser.parseComposedLens(Lexer(getContextStringParts(c)))

    val finalTree: Interpret[c.Tree] =
      for {
        ast <- Parse.fromEither(errorOrAst)
        tree <- interpretComposedLens(c)(ast, AccessMode.GetAndSet, applied = false)
      } yield tree

    val (errorOrTree, infos) = finalTree.eval(args.toList)
    infos.map(_.pretty).foreach(println)
    errorOrTree match {
      case Left(err) => c.abort(c.enclosingPosition, err.toString)
      case Right(tree) => tree
    }
  }

  private def interpretComposedLens(c: whitebox.Context)(composedLens: ComposedLens, mode: AccessMode, applied: Boolean): Parse[c.Type, c.Expr[Any], c.Tree] = {
    import c.universe._

    type Interpret[A] = Parse[c.Type, c.Expr[Any], A]

    def composeAll(code: c.Tree, lensExprs: List[LensExpr]): Interpret[c.Tree] = {
      def compose(codeSoFar: c.Tree, lensExpr: LensExpr): Interpret[c.Tree] = {
        for {
          nextLensCode <- interpretLensExpr(lensExpr)
          lastInfo <- getLastParseInfo(show(codeSoFar))
        } yield q"($codeSoFar).${TermName(lastInfo.opticType.composeVerb)}($nextLensCode)"
      }

      lensExprs match {
        case lx :: lxs => compose(code, lx).flatMap(composeAll(_, lxs))
        case Nil => Parse.pure(code)
      }
    }


    def interpretLensExpr(lexpr: LensExpr): Interpret[c.Tree] = {
      def getOpticTypeFromArg(actualType: c.Type): Interpret[OpticType] =
        actualType.erasure.typeSymbol.name.toString match {
          case "Fold" => Parse.pure(OpticType.FoldType)
          case "Getter" => Parse.pure(OpticType.GetterType)
          case "PSetter" => Parse.pure(OpticType.SetterType)
          case "PTraversal" => Parse.pure(OpticType.TraversalType)
          case "POptional" => Parse.pure(OpticType.OptionalType)
          case "PPrism" => Parse.pure(OpticType.PrismType)
          case "PLens" => Parse.pure(OpticType.LensType)
          case "PIso" => Parse.pure(OpticType.IsoType)
          case n => Parse.raiseError(OpticTypecheckFailed(actualType.toString))
        }

      def patternMatchOrElse[A, R](a: A, orElse: => ParseError)(pf: PartialFunction[A,R]): Interpret[R] =
        if (pf.isDefinedAt(a)) Parse.pure(pf(a))
        else Parse.raiseError(orElse)

      def getLastOutputType(name: String): Interpret[c.Type] =
        for {
          info <- Parse.getLastParseInfo[c.Type, c.Expr[Any]](MissingInputType(name))
        } yield info.outType

      def getInputOutputTypes(actualType: c.Type, opticType: OpticType): Interpret[(c.Type, c.Type)] = {
        val typeArgs = actualType.typeArgs
        if (opticType.polymorphic && typeArgs.length == 4) {
          Parse.pure((typeArgs(0), typeArgs(2)))
        } else if (typeArgs.length == 2) {
          Parse.pure((typeArgs(0), typeArgs(1)))
        } else {
          Parse.raiseError(OpticTypecheckFailed(actualType.toString))
        }
      }

      def typeCheckOrElse(tree: c.Tree, orElse: => ParseError): Interpret[c.Tree] = {
        val typed = c.typecheck(tree, silent = true)
        if (typed.isEmpty) Parse.raiseError(orElse)
        else Parse.pure(typed)
      }

      def checkOrElse[A](a: A, orElse: => ParseError)(p: A => Boolean): Interpret[A] =
        if (p(a)) Parse.pure(a)
        else Parse.raiseError(orElse)

      def isGetter(name: String)(sym: c.Symbol): Boolean = {
        sym.name == TermName(name) &&
          sym.isMethod &&
          sym.asMethod.paramLists.flatten == Nil
      }

      def interpretNamedRefGetter(name: String): Interpret[c.Tree] = {
        for {
          inputType <- getLastOutputType(name)
          getter <- checkOrElse(inputType.member(TermName(name)), InvalidGetter(name))(isGetter(name))
          outputType = getter.info
          _ <- storeParseInfo(s".$name", inputType, outputType, GetterType)
        } yield  q"_root_.monocle.Getter(_.${TermName(name)})"
      }

      def interpretNamedRefSetter(name: String): Interpret[c.Tree] = {
        def isValidCopyMethod(sym: c.Symbol): Boolean = {
          sym.name == TermName("copy") &&
            sym.isMethod &&
            sym.asMethod.paramLists.length == 1
        }

        def getSetterType(method: c.Symbol): Option[c.Type] = {
          if (isValidCopyMethod(method)) {
            for {
              paramList <- method.asMethod.paramLists.headOption
              param <- paramList.find(_.name == TermName(name))
            } yield param.info
          } else None
        }

        for {
          inputType <- getLastOutputType(name)
          copyMethod = inputType.member(TermName("copy")).asMethod
          outputType <- Parse.fromOption(getSetterType(copyMethod), InvalidSetter(name))
          _ <- storeParseInfo(s".$name", inputType, outputType, SetterType)
        } yield {
          q"_root_.monocle.Setter[$inputType, $outputType](f => s => s.copy(${TermName(name)} = f(s.${TermName(name)})))"
        }
      }

      def interpretNamedRefGetterSetter(name: String): Interpret[c.Tree] = {
        for {
          inputType <- getLastOutputType(name)
          getter <- checkOrElse(inputType.member(TermName(name)), InvalidGetter(name))(isGetter(name))
          outputType = getter.info
          _ <- storeParseInfo(s".$name", inputType, outputType, LensType)
        } yield q"_root_.monocle.Lens(_.${TermName(name)})(a => (s: $inputType) => s.copy(${TermName(name)} = a))"
      }

      def interpretInterpolatedLens: Interpret[c.Tree] =
        for {
          arg <- Parse.popArg[c.Type, c.Expr[Any]]
          opticType <- getOpticTypeFromArg(arg.actualType)
          io <- getInputOutputTypes(arg.actualType, opticType)
          (inType, outType) = io
          _ <- storeParseInfo(s"$$${show(arg.tree)}", inType, outType, opticType)
        } yield arg.tree



      def interpretEach: Interpret[c.Tree] = {
        object ImplicitEachTargetType {
          object InnerType {
            def unapply(t: c.Tree): Option[c.Type] = t.tpe match {
              case TypeRef(_, _, List(_, TypeRef(_, sym, _))) => Some(sym.asType.toType)
              case _ => None
            }
          }
          def unapply(tree: c.Tree): Option[c.Type] = tree match {
            case Apply(_, List(InnerType(next))) => Some(next)
            case _ => None
          }
        }

        for {
          inputType <- getLastOutputType("*")
          untypedTree = q"implicitly[_root_.monocle.function.Each[$inputType, _]]"
          typedTree <- typeCheckOrElse(untypedTree, ImplicitEachNotFound(inputType.toString))
          outputType <- patternMatchOrElse(typedTree, ImplicitEachNotFound(inputType.toString)) {
            case ImplicitEachTargetType(nextType) => nextType
          }
          _ <- storeParseInfo("*", inputType, outputType, TraversalType)
        } yield q"_root_.monocle.function.Each.each"
      }

      def interpretPossible: Interpret[c.Tree] = {
        object ImplicitPossibleTargetType {
          object InnerType {
            def unapply(t: c.Tree): Option[c.Type] = t.tpe match {
              case TypeRef(_, _, List(_, TypeRef(_, sym, _))) => Some(sym.asType.toType)
              case _ => None
            }
          }
          def unapply(tree: c.Tree): Option[c.Type] = tree match {
            case Apply(_, List(InnerType(next))) => Some(next)
            case _ => None
          }
        }
        for {
          inputType <- getLastOutputType("?")
          untypedTree = q"implicitly[_root_.monocle.function.Possible[$inputType, _]]"
          typedTree <- typeCheckOrElse(untypedTree, ImplicitPossibleNotFound(inputType.toString))
          outputType <- patternMatchOrElse(typedTree, ImplicitPossibleNotFound(inputType.toString)) {
            case ImplicitPossibleTargetType(nextType) => nextType
          }
          _ <- storeParseInfo("?", inputType, outputType, OptionalType)
        } yield q"_root_.monocle.function.Possible.possible"
      }

      def interpretIndex(i: c.Tree): Interpret[c.Tree] = {
        object ImplicitIndexTargetType {
          object InnerType {
            def unapply(t: c.Tree): Option[c.Type] = t.tpe match {
              case TypeRef(_, _, List(_, _, TypeRef(_, sym, _))) => Some(sym.asType.toType)
              case _ => None
            }
          }
          def unapply(tree: c.Tree): Option[c.Type] = tree match {
            case Apply(_, List(InnerType(next))) => Some(next)
            case _ => None
          }
        }

        val label = s"[${show(i)}]"
        for {
          inputType <- getLastOutputType(label)
          untypedTree = q"implicitly[_root_.monocle.function.Index[$inputType,_,_]]"
          typedTree <- typeCheckOrElse(untypedTree, ImplicitIndexNotFound(inputType.toString))
          outputType <- patternMatchOrElse(typedTree, ImplicitIndexNotFound(inputType.toString)) {
            case ImplicitIndexTargetType(nextType) => nextType
          }
          _ <- storeParseInfo(label, inputType, outputType, OptionalType)
        } yield q"_root_.monocle.function.Index.index($i)"
      }

      lexpr match {
        case RefExpr(NamedLensRef(name)) => mode match {
          case AccessMode.Get => interpretNamedRefGetter(name)
          case AccessMode.Set => interpretNamedRefSetter(name)
          case AccessMode.GetAndSet => interpretNamedRefGetterSetter(name)
        }
        case RefExpr(InterpLensRef) => interpretInterpolatedLens
        case EachExpr => interpretEach
        case OptExpr => interpretPossible
        case IndexedExpr(LiteralIndex(i)) => interpretIndex(q"$i")
        case IndexedExpr(InterpIndex) => Parse.popArg.flatMap(i => interpretIndex(i.tree))
      }
    }

    def getLastParseInfo(name: String): Interpret[ParseInfo[c.Type]] = {
      Parse.getLastParseInfo[c.Type, c.Expr[Any]](MissingInputType(name))
    }

    def storeParseInfo(name: String, inType: c.Type, outType: c.Type, opticType: OpticType): Interpret[Unit] = {
      for {
        lastInfo <- getLastParseInfo(name)
        composed <- Parse.fromOption(lastInfo.compositeOpticType.compose(opticType),
                                     InvalidOpticComposition(lastInfo.compositeOpticType, opticType))
        _ <- Parse.storeParseInfo(ParseInfo(name, inType, outType, opticType, composed))
      } yield ()
    }

    def interpretSource: Interpret[c.Tree] = {
      for {
        arg <- Parse.popArg[c.Type, c.Expr[Any]]
        _ <- Parse.storeParseInfo(ParseInfo(s"$$arg", typeOf[Unit], arg.actualType, IsoType, IsoType))
      } yield q"_root_.goggles.macros.MonocleMacros.const($arg)"
    }

    val (initCode, lensExprs) =
      if (applied) (interpretSource, composedLens.toList)
      else (interpretLensExpr(composedLens.head), composedLens.tail)

    initCode.flatMap(composeAll(_, lensExprs))
  }

  def const[A,B](a: => A) = PIso[Unit, B, A ,B](_ => a)(identity)

  private def getContextStringParts(implicit c: whitebox.Context): List[String] = {
    import c.universe._

    c.prefix.tree match {
      case Apply(f, List(Apply(g, rawParts))) => rawParts.map {
        case Literal(Constant(str: String)) => str
      }
    }
  }
}
