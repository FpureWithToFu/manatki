package manatki.data.eval.stage

import cats.data.IndexedState
import cats.effect.ExitCase
import cats.evidence.Is
import cats.{Functor, Monad, MonadError, Monoid, StackSafeMonad, ~>}
import manatki.free.FunK
import tofu.optics.PContains
import tofu.syntax.monadic._
import CalcMSpecials._

import scala.annotation.tailrec

sealed trait CalcM[+F[+_], -R, -S1, +S2, +O, +E, +A] {
  def narrowRead[R1 <: R]: CalcM[F, R1, S1, S2, O, E, A] = this
  def mapK[G[+_]: Functor](fk: FunK[F, G]): CalcM[G, R, S1, S2, O, E, A]
}

object CalcM {
  def unit[S]: CalcM[Nothing, Any, S, S, Nothing, Nothing, Unit]        = Pure(())
  def pure[S, A](a: A): CalcM[Nothing, Any, S, S, Nothing, Nothing, A]  = Pure(a)
  def read[S, R]: CalcM[Nothing, R, S, S, Nothing, Nothing, R]          = Read()
  def get[S]: CalcM[Nothing, Any, S, S, Nothing, Nothing, S]            = Get()
  def set[S](s: S): CalcM[Nothing, Any, Any, S, Nothing, Nothing, Unit] = Set(s)
  def update[S1, S2](f: S1 => S2): CalcM[Nothing, Any, S1, S2, Nothing, Nothing, Unit] =
    get[S1].flatMapS(s => set(f(s)))
  def raise[S, E](e: E): CalcM[Nothing, Any, S, S, Nothing, E, Nothing]    = Raise(e)
  def defer[F[+_], R, S1, S2, O, E, A](x: => CalcM[F, R, S1, S2, O, E, A]) = Defer(() => x)
  def delay[S, A](x: => A): CalcM[Nothing, Any, S, S, Nothing, Nothing, A] = defer(pure[S, A](x))

  def write[S](s: S)(implicit S: Monoid[S]): CalcM[Nothing, Any, S, S, Nothing, Nothing, Unit] = update(S.combine(_, s))

  sealed trait CalcMRes[-R, -S1, +S2, +O, +E, +A] extends CalcM[Nothing, R, S1, S2, O, E, A] {
    def submit[X](submit: Submit[R, S1, S2, E, A, X]): X
    def mapK[G[+_]: Functor](fk: FunK[Nothing, G]): CalcM[G, R, S1, S2, O, E, A] = this
  }
  final case class Pure[S, +A](a: A) extends CalcMRes[Any, S, S, Nothing, Nothing, A] {
    def submit[X](submit: Submit[Any, S, S, Nothing, A, X]): X = submit.success(submit.state, a)
  }
  final case class Read[S, R]() extends CalcMRes[R, S, S, Nothing, Nothing, R] {
    def submit[X](submit: Submit[R, S, S, Nothing, R, X]): X = submit.success(submit.state, submit.read)
  }
  final case class Get[S]() extends CalcMRes[Any, S, S, Nothing, Nothing, S] {
    def submit[X](submit: Submit[Any, S, S, Nothing, S, X]): X = submit.success(submit.state, submit.state)
  }
  final case class Set[S](s: S) extends CalcMRes[Any, Any, S, Nothing, Nothing, Unit] {
    def submit[X](submit: Submit[Any, Any, S, Nothing, Unit, X]): X = submit.success(s, ())
  }
  final case class Raise[S, E](e: E) extends CalcMRes[Any, S, S, Nothing, E, Nothing] {
    def submit[X](submit: Submit[Any, S, S, E, Nothing, X]): X = submit.error(submit.state, e)
  }
  final case class Defer[+F[+_], -R, -S1, +S2, +O, +E, +A](runStep: () => CalcM[F, R, S1, S2, O, E, A])
      extends CalcM[F, R, S1, S2, O, E, A] {
    def mapK[G[+_]: Functor](fk: FunK[F, G]): CalcM[G, R, S1, S2, O, E, A] = Defer(() => runStep().mapK(fk))
  }
  final case class Output[+F[+_], -R, -S1, +S2, +O, +E, +A](
      output: O,
      next: CalcM[F, R, S1, S2, O, E, A]
  ) extends CalcM[F, R, S1, S2, O, E, A]

  sealed trait ProvideM[+F[+_], R, -S1, +S2, +O, +E, +A] extends CalcM[F, R, S1, S2, O, E, A] {
    type R1
    def r: R1
    def inner: CalcM[F, R1, S1, S2, O, E, A]
    def any: R Is Any
  }

  final case class Provide[+F[+_], R, -S1, +S2, O, +E, +A](r: R, inner: CalcM[F, R, S1, S2, O, E, A])
      extends ProvideM[F, Any, S1, S2, O, E, A] {
    type R1 = R
    def any                                                                  = Is.refl
    def mapK[G[+_]: Functor](fk: FunK[F, G]): CalcM[G, Any, S1, S2, O, E, A] = Provide(r, inner.mapK(fk))
  }

  final case class Sub[+F[+_], -R, -S1, +S2, +O, +E, +A](fc: F[CalcM[F, R, S1, S2, O, E, A]])
      extends CalcM[F, R, S1, S2, O, E, A] {
    def mapK[G[+_]: Functor](fk: FunK[F, G]): CalcM[G, R, S1, S2, O, E, A] = Sub(fk(fc).map(_.mapK(fk)))
  }

  final case class Bind[+F[+_], R, S1, S2, S3, O1, O2, E1, E2, A, B](
      src: CalcM[F, R, S1, S2, O1, E1, A],
      continue: Continue[A, O1, E1, CalcM[F, R, S2, S3, O2, E2, B]],
  ) extends CalcM[F, R, S1, S3, O2, E2, B] {
    type MidState = S2
    type MidErr   = E1
    type MidVal   = A
    type MidOut   = O1

    def mapK[G[+_]: Functor](fk: FunK[F, G]): CalcM[G, R, S1, S3, O2, E2, B] =
      Bind(
        src.mapK(fk),
        new Continue[A, O1, E1, CalcM[G, R, S2, S3, O2, E2, B]] {
          def success(result: A): CalcM[G, R, S2, S3, O2, E2, B] = continue.success(result).mapK(fk)
          def error(err: E1): CalcM[G, R, S2, S3, O2, E2, B]     = continue.error(err).mapK(fk)
          def output(out: O1): ContinueOrFinish[A, O1, E1, CalcM[G, R, S2, S3, O2, E2, B]] =
            continue.output(out).map(_.mapK(fk))
        }
      )
  }

  implicit class invariantOps[F[+_], R, S1, S2, E, A](private val calc: CalcM[F, R, S1, S2, E, A]) extends AnyVal {
    final def step(r: R, init: S1)(implicit F: Functor[F]): StepResult[F, S2, E, A] =
      CalcM.step(calc, r, init)

    final def runTailRec(r: R, init: S1)(implicit F: Monad[F]): F[(S2, Either[E, A])] =
      F.tailRecM(calc.provideSet(r, init)) { c =>
        c.step((), ()) match {
          case now: StepResult.Now[S2, E, A]            => F.pure(Right((now.state, now.result)))
          case wrap: StepResult.Wrap[F, r, s, S2, E, A] => F.map(wrap.provided(F))(Left(_))
        }
      }

    final def runUnit(init: S1)(implicit ev: Unit <:< R, F: Functor[F]): StepResult[F, S2, E, A] = step((), init)

    def bind[R1 <: R, E2, S3, B](continue: Continue[A, E, CalcM[F, R1, S2, S3, E2, B]]): CalcM[F, R1, S1, S3, E2, B] =
      Bind(calc, continue)
    def flatMap[R1 <: R, E1 >: E, B](f: A => CalcM[F, R1, S2, S2, E1, B]): CalcM[F, R1, S1, S2, E1, B] =
      bind(Continue.flatMapConst[A, E, S2, CalcM[F, R1, S2, S2, E1, B]](f))
    def >>=[R1 <: R, E1 >: E, B](f: A => CalcM[F, R1, S2, S2, E1, B]) = flatMap(f)
    def >>[R1 <: R, E1 >: E, B](c: => CalcM[F, R1, S2, S2, E1, B])    = flatMap(_ => c)
    def handleWith[E1](f: E => CalcM[F, R, S2, S2, E1, A]): CalcM[F, R, S1, S2, E1, A] =
      bind(Continue.handleWithConst[A, E, S2, CalcM[F, R, S2, S2, E1, A]](f))
    def handle(f: E => A): CalcM[F, R, S1, S2, E, A]            = handleWith(e => pure(f(e)))
    def map[B](f: A => B): CalcM[F, R, S1, S2, E, B]            = flatMap(a => pure(f(a)))
    def as[B](b: => B): CalcM[F, R, S1, S2, E, B]               = map(_ => b)
    def mapError[E1](f: E => E1): CalcM[F, R, S1, S2, E1, A]    = handleWith(e => CalcM.raise(f(e)))
    def provideSet(r: R, s: S1): CalcM[F, Any, Any, S2, E, A]   = set(s) *>> calc.provide(r)
    def provide(r: R): CalcM[F, Any, S1, S2, E, A]              = Provide(r, calc)
    def provideSome[R1](f: R1 => R): CalcM[F, R1, S1, S2, E, A] = read[S1, R1] flatMapS (r => calc.provide(f(r)))

    def focus[S3, S4](lens: PContains[S3, S4, S1, S2]): CalcM[F, R, S3, S4, E, A] =
      get[S3].flatMapS { s3 =>
        set(lens.extract(s3)) *>> calc.bind(
          new Continue[A, E, CalcM[F, R, S2, S4, E, A]] {
            def success(result: A): CalcM[F, R, S2, S4, E, A] =
              get[S2].flatMapS(s2 => set(lens.set(s3, s2)) *>> pure(result))
            def error(err: E): CalcM[F, R, S2, S4, E, A] = get[S2].flatMapS(s2 => set(lens.set(s3, s2)) *>> raise(err))
          }
        )
      }
  }

  implicit class CalcSuccessfullOps[F[+_], R, S1, S2, A](private val calc: CalcM[F, R, S1, S2, Nothing, A])
      extends AnyVal {
    final def flatMapS[R1 <: R, S3, B, E](f: A => CalcM[F, R1, S2, S3, E, B]): CalcM[F, R1, S1, S3, E, B] =
      calc.bind(Continue.flatMapSuccess[A, B, S2, S3, CalcM[F, R1, S2, S3, E, B]](f))
    final def productRS[R1 <: R, S3, B, E](r: => CalcM[F, R1, S2, S3, E, B]): CalcM[F, R1, S1, S3, E, B] =
      flatMapS(_ => r)
    final def *>>[R1 <: R, S3, B, E](r: => CalcM[F, R1, S2, S3, E, B]): CalcM[F, R1, S1, S3, E, B] = productRS(r)
  }

  implicit class CalcPureOps[R, S1, S2, E, A](private val calc: CalcM[Nothing, R, S1, S2, E, A]) extends AnyVal {
    final def run(r: R, init: S1): (S2, Either[E, A]) =
      calc.step(r, init) match {
        case StepResult.Wrap(_, _, n) => n: Nothing
        case StepResult.Error(s, err) => (s, Left(err))
        case StepResult.Ok(s, a)      => (s, Right(a))
      }
  }

  implicit class CalcPureSuccessfullOps[R, S1, S2, A](private val calc: CalcM[Nothing, R, S1, S2, Nothing, A])
      extends AnyVal {
    final def runSuccess(r: R, init: S1): (S2, A) =
      calc.step(r, init) match {
        case StepResult.Wrap(_, _, n) => n: Nothing
        case StepResult.Error(_, err) => err
        case StepResult.Ok(s, a)      => (s, a)
      }
  }

  implicit class CalcUnsuccessfullOps[F[+_], R, S1, S2, E](private val calc: CalcM[F, R, S1, S2, E, Nothing])
      extends AnyVal {
    def handleWithS[R1 <: R, E1, S3, B, A](f: E => CalcM[F, R, S2, S3, E1, A]): CalcM[F, R1, S1, S3, E1, A] =
      calc.bind(Continue.handleWithFail[E, E1, S2, S3, CalcM[F, R, S2, S3, E1, A]](f))
  }

  implicit class CalcFixedStateOps[F[+_], R, S, E, A](private val calc: CalcM[F, R, S, S, E, A]) extends AnyVal {
    def when(b: Boolean): CalcM[F, R, S, S, E, Any] = if (b) calc else CalcM.unit
  }

  implicit class CalcSimpleStateOps[F[+_], S1, S2, A](private val calc: CalcM[Nothing, Any, S1, S2, Nothing, A])
      extends AnyVal {
    final def runSuccessUnit(init: S1): (S2, A) = calc.runSuccess((), init)

    def toState: IndexedState[S1, S2, A] = IndexedState(runSuccessUnit)
  }

  @tailrec
  def step[F[+_], R, S1, S2, E, A](calc: CalcM[F, R, S1, S2, E, A], r: R, init: S1)(
      implicit F: Functor[F]
  ): StepResult[F, S2, E, A] =
    calc match {
      case res: CalcMRes[R, S1, S2, E, A] =>
        res.submit(new Submit[R, S1, S2, E, A, StepResult[F, S2, E, A]](r, init) {
          def success(s: S2, a: A) = StepResult.Ok(s, a)
          def error(s: S2, err: E) = StepResult.Error(s, err)
        })
      case d: Defer[F, R, S1, S2, E, A]   => step(d.runStep(), r, init)
      case sub: Sub[F, R, S1, S2, E, A]   => StepResult.Wrap(r, init, sub.fc)
      case p: Provide[F, r, S1, S2, E, A] => step[F, r, S1, S2, E, A](p.inner, p.r, init)
      case c1: Bind[F, R, S1, s1, S2, e1, E, a1, A] =>
        c1.src match {
          case res: CalcMRes[R, S1, c1.MidState, e1, a1] =>
            val (sm, next) =
              res.submit[(s1, CalcM[F, R, s1, S2, E, A])](
                new Submit[R, S1, c1.MidState, e1, a1, (c1.MidState, CalcM[F, R, c1.MidState, S2, E, A])](r, init) {
                  def success(s: c1.MidState, a: a1) = (s, c1.continue.success(a))
                  def error(s: c1.MidState, err: e1) = (s, c1.continue.error(err))
                }
              )
            step[F, R, s1, S2, E, A](next, r, sm)
          case d: Defer[F, R, S1, _, _, _] => step(d.runStep().bind(c1.continue), r, init)
          case sub: Sub[F, R, S1, _, _, _] =>
            StepResult.Wrap[F, R, S1, S2, E, A](r, init, F.map(sub.fc)(_.bind(c1.continue)))
          case p: ProvideM[F, R, S1, _, _, _] =>
            val kcont = p.any.substitute[λ[r => Continue[a1, e1, CalcM[F, r, s1, S2, E, A]]]](c1.continue)

            step(p.inner.bind[p.R1, E, S2, A](kcont), p.r, init)
          case c2: Bind[F, R, S1, s2, _, e2, _, a2, _] =>
            step(c2.src.bind(Continue.compose(c2.continue, c1.continue)), r, init)
        }
    }

  implicit def calcInstance[F[+_], R, S, E]: CalcFunctorInstance[F, R, S, E] = new CalcFunctorInstance[F, R, S, E]

  class CalcFunctorInstance[F[+_], R, S, E]
      extends MonadError[CalcM[F, R, S, S, E, *], E] with cats.Defer[CalcM[F, R, S, S, E, *]]
      with StackSafeMonad[CalcM[F, R, S, S, E, *]] with cats.effect.Bracket[CalcM[F, R, S, S, E, *], E] {
    def defer[A](fa: => CalcM[F, R, S, S, E, A]): CalcM[F, R, S, S, E, A] = CalcM.defer(fa)
    def raiseError[A](e: E): CalcM[F, R, S, S, E, A]                      = CalcM.raise(e)
    def handleErrorWith[A](fa: CalcM[F, R, S, S, E, A])(f: E => CalcM[F, R, S, S, E, A]): CalcM[F, R, S, S, E, A] =
      fa.handleWith(f)
    def flatMap[A, B](fa: CalcM[F, R, S, S, E, A])(f: A => CalcM[F, R, S, S, E, B]): CalcM[F, R, S, S, E, B] =
      fa.flatMap(f)
    def pure[A](x: A): CalcM[F, R, S, S, E, A] = CalcM.pure(x)
    def bracketCase[A, B](
        acquire: CalcM[F, R, S, S, E, A]
    )(
        use: A => CalcM[F, R, S, S, E, B]
    )(release: (A, ExitCase[E]) => CalcM[F, R, S, S, E, Unit]): CalcM[F, R, S, S, E, B] =
      acquire.flatMap { a =>
        use(a).bind(new Continue[B, E, CalcM[F, R, S, S, E, B]] {
          def success(result: B): CalcM[F, R, S, S, E, B] = release(a, ExitCase.Completed).as(result)
          def error(err: E): CalcM[F, R, S, S, E, B]      = release(a, ExitCase.Error(err)) >> CalcM.raise(err)
        })
      }
  }
}

object CalcMSpecials {
  sealed trait ContinueOrFinish[-A, -E, -O, +T] {
    def map[T1](f: T => T1): ContinueOrFinish[A, O, E, T1]
  }

  final case class Finish[T](c: T) extends ContinueOrFinish[Any, Any, Any, T] {
    def map[T1](f: T => T1): Finish[T1] = Finish(f(c))
  }

  trait Continue[-A, -O, -E, +T] extends ContinueOrFinish[A, O, E, T] { self =>
    def success(result: A): T
    def error(err: E): T
    def output(out: O): ContinueOrFinish[A, O, E, T]
    def map[T1](f: T => T1): Continue[A, E, O, T1] = new Continue[A, O, E, T1] {
      def success(result: A): T1                        = f(self.success(result))
      def error(err: E): T1                             = f(self.error(err))
      def output(out: O): ContinueOrFinish[A, O, E, T1] = self.output(out).map(f)
    }
  }

  object Continue {
    def compose[A, B, C, E, V, W, R, S1, S2, S3, F[+_]](
        c1: Continue[A, E, CalcM[F, R, S1, S2, V, B]],
        c2: Continue[B, V, CalcM[F, R, S2, S3, W, C]]
    ): Continue[A, E, CalcM[F, R, S1, S3, W, C]] =
      new Continue[A, E, CalcM[F, R, S1, S3, W, C]] {
        def success(result: A): CalcM[F, R, S1, S3, W, C] = c1.success(result).bind(c2)
        def error(err: E): CalcM[F, R, S1, S3, W, C]      = c1.error(err).bind(c2)
      }

    def flatMapConst[A, E, O, S, X >: CalcM[Nothing, Any, S, S, O, E, Nothing]](f: A => X): Continue[A, O, E, X] =
      new Continue[A, O, E, X] {
        def success(result: A): X                        = f(result)
        def error(err: E): X                             = CalcM.Raise[S, E](err)
        def output(out: O): ContinueOrFinish[A, O, E, X] = this
      }

    def handleWithConst[A, E, O, S, X >: CalcM[Nothing, Any, S, S, O, Nothing, A]](f: E => X): Continue[A, O, E, X] =
      new Continue[A, O, E, X] {
        def success(result: A): X                        = CalcM.Pure[S, A](result)
        def error(err: E): X                             = f(err)
        def output(out: O): ContinueOrFinish[A, O, E, X] = this
      }

    def flatMapSuccess[A, B, O, S1, S2, X >: CalcM[Nothing, Any, S1, S2, O, Nothing, B]](
        f: A => X
    ): Continue[A, O, Nothing, X] =
      new Continue[A, O, Nothing, X] {
        def success(result: A): X                              = f(result)
        def error(err: Nothing): X                             = err
        def output(out: O): ContinueOrFinish[A, O, Nothing, X] = this
      }

    def handleWithFail[E, V, S1, S2, O, X >: CalcM[Nothing, Any, S1, S2, O, V, Nothing]](
        f: E => X
    ): Continue[Nothing, O, E, X] =
      new Continue[Nothing, O, E, X] {
        def success(result: Nothing): X                        = result
        def error(err: E): X                                   = f(err)
        def output(out: O): ContinueOrFinish[Nothing, O, E, X] = this
      }
  }

  sealed trait StepResult[+F[+_], +S, +E, +A]

  object StepResult {
    sealed trait Now[+S, +E, +A] extends StepResult[Nothing, S, E, A] {
      def state: S
      def result: Either[E, A] = this match {
        case Ok(_, a)    => Right(a)
        case Error(_, e) => Left(e)
      }
    }

    final case class Ok[+S, +A](state: S, value: A)    extends Now[S, Nothing, A]
    final case class Error[+S, +E](state: S, error: E) extends Now[S, E, Nothing]
    final case class Wrap[F[+_], R, S1, +S2, +E, +A](input: R, state: S1, inner: F[CalcM[F, R, S1, S2, E, A]])
        extends StepResult[F, S2, E, A] {
      def provided(implicit F: Functor[F]): F[CalcM[F, Any, Any, S2, E, A]] = F.map(inner)(_.provideSet(input, state))
    }
  }

  abstract class Submit[+R, +S1, -S2, -E, -A, +X](val read: R, val state: S1) {
    def success(s: S2, a: A): X
    def error(s: S2, err: E): X
  }
}
