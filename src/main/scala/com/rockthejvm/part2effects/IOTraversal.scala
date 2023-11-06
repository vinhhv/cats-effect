package com.rockthejvm.part2effects

import cats.Traverse
import cats.effect.{IO, IOApp}

import scala.concurrent.Future
import scala.util.Random

object IOTraversal extends IOApp.Simple {

  import scala.concurrent.ExecutionContext.Implicits.global

  def heavyComputation(string: String): Future[Int] = Future {
    Thread.sleep(Random.nextInt(1000))
    string.split(" ").length
  }

  val workLoad: List[String] = List("I quite like CE", "Scala is great", "looking forward to some awesome stuff")

  def clunkyFutures(): Unit = {
    val futures: List[Future[Int]] = workLoad.map(heavyComputation)
    // Future[List[Int]] would be hard to obtain
    futures.foreach(_.foreach(println))
  }

  import cats.instances.list.*
  val listTraverse = Traverse[List]

  def traverseFutures(): Unit = {
    // traverse
    val singleFuture: Future[List[Int]] = listTraverse.traverse(workLoad)(heavyComputation)
    // ^^ this stores ALL the results
    singleFuture.foreach(println)
  }

  import com.rockthejvm.utils.*

  // traverse for IO
  def computeAsIO(string: String): IO[Int] = IO {
    Thread.sleep(Random.nextInt(1000))
    string.split(" ").length
  }.debugs

  val ios: List[IO[Int]]      = workLoad.map(computeAsIO)
  val singleIO: IO[List[Int]] = listTraverse.traverse(workLoad)(computeAsIO)

  // parallel traversal
  import cats.syntax.parallel.*
  val parallelSingleIO: IO[List[Int]] = workLoad.parTraverse(computeAsIO)

  /**
   * Exercises
   */
  def sequence[A](ios: List[IO[A]]): IO[List[A]] = listTraverse.traverse(ios)(identity)

  def sequence_v2[F[_]: Traverse, A](ios: F[IO[A]]): IO[F[A]] = Traverse[F].traverse(ios)(identity)

  def parSequence[A](ios: List[IO[A]]): IO[List[A]] = ios.parTraverse(identity)

  def parSequence_v2[F[_]: Traverse, A](ios: F[IO[A]]): IO[F[A]] = ios.parTraverse(identity)

  // existing sequence API
  val singleIO_v2: IO[List[Int]] = listTraverse.sequence(ios)

  // parallel sequencing
  val parallelSingleIO_v2: IO[List[Int]] = parSequence(ios)
  val parallelSingleIO_v3: IO[List[Int]] = ios.parSequence

  override def run: IO[Unit] = parallelSingleIO_v3.map(_.sum).debugs.void
}
