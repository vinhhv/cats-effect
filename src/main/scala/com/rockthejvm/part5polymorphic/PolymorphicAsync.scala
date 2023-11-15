package com.rockthejvm.part5polymorphic

import cats.effect.kernel.Sync
import cats.effect.{Async, Concurrent, IO, IOApp, Temporal}
import com.rockthejvm.utils.*

import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext

object PolymorphicAsync extends IOApp.Simple {

  // Async - asynchronous computations, "suspended" in F
  trait MyAsync[F[_]] extends Sync[F] with Temporal[F] {
    // fundamental description of async computations
    def executionContext: F[ExecutionContext]
    def async[A](cb: (Either[Throwable, A] => Unit) => F[Option[F[Unit]]]): F[A]
    def evalOn[A](fa: F[A], ec: ExecutionContext): F[A]

    def async_[A](cb: (Either[Throwable, A] => Unit) => Unit): F[A] =
      async(kb => map(pure(cb(kb)))(_ => None))
    def never[A]: F[A] = async_(_ => ()) // never-ending effect
  }

  val asyncIO = Async[IO] // given Async[IO]

  // pure, map/flatMap, raiseError, uncancelable, start (Spawn), ref/deferred, sleep, delay/defer/blocking, +
  val ec = asyncIO.executionContext

  // power: async_ + async: FFI
  val threadPool = Executors.newFixedThreadPool(10)
  type Callback[A] = Either[Throwable, A] => Unit
  val asyncMeaningOfLife: IO[Int] = IO.async_ { (cb: Callback[Int]) =>
    // start computation on some other thread pool
    threadPool.execute { () =>
      println(s"[${Thread.currentThread().getName}] Computing an async MOL")
      cb(Right(42))
    }
  } // same

  val asyncMeaningOfLife_v2: IO[Int] = asyncIO.async_ { (cb: Callback[Int]) =>
    // start computation on some other thread pool
    threadPool.execute { () =>
      println(s"[${Thread.currentThread().getName}] Computing an async MOL")
      cb(Right(42))
    }
  }

  val asyncMeaningOfLifeComplex: IO[Int] = IO.async { (cb: Callback[Int]) =>
    IO {
      threadPool.execute { () =>
        println(s"[${Thread.currentThread().getName}] Computing an async MOL")
        cb(Right(42))
      }
    }.as(Some(IO("Cancelled").debugs.void)) // <- finalizer in case the computation gets cancelled
  }

  val myExecutionContext    = ExecutionContext.fromExecutorService(threadPool)
  val asyncMeaningOfLife_V3 = asyncIO.evalOn(IO(42).debugs, myExecutionContext).guarantee(IO(threadPool.shutdown()))

  // never
  val neverIO = asyncIO.never

  /**
   * Exercises
   * 1 - implement never and async_ in terms of async.
   * 2 - tuple two effects with different requirements.
   */
  // 1
  def async_[F[_], A](cb: (Either[Throwable, A] => Unit) => Unit)(using as: Async[F]): F[A] =
    as.async(result => as.as(as.delay(cb(result)), None))

  def never[F[_], A](using as: Async[F]): F[A] =
    as.async(_ => as.pure(Some(as.unit)))

  // 2
  def firstEffect[F[_]: Concurrent, A](a: A): F[A] = Concurrent[F].pure(a)
  def secondEffect[F[_]: Sync, A](a: A): F[A]      = Sync[F].pure(a)

  import cats.syntax.flatMap.*
  import cats.syntax.functor.*
  def tupledEffect[F[_]: Async, A](a: A): F[(A, A)] =
    for {
      e1 <- firstEffect(a)
      e2 <- secondEffect(a)
    } yield (e1, e2)

  override def run: IO[Unit] = ???
}
