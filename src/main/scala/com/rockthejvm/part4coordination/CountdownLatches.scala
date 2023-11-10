package com.rockthejvm.part4coordination

import cats.effect.{IO, IOApp, Resource}
import cats.effect.std.CountDownLatch
import cats.syntax.parallel.*
import cats.syntax.traverse.*
import com.rockthejvm.utils.*

import java.io.{File, FileWriter}
import scala.concurrent.duration.*
import scala.io.Source

object CountdownLatches extends IOApp.Simple {

  /*
    CDLatches are a coordination primitive initialized with a count.
    All fibers calling await() on the CDLatch are (semantically) blocked.
    When the internal count of the latch reaches 0 (via release() calls from other fibers), all waiting fibers are unlocked.
   */

  def announcer(latch: CountDownLatch[IO]): IO[Unit] =
    for {
      _ <- IO("Starting race shortly...").debugs >> IO.sleep(2.seconds)
      _ <- IO("5...").debugs >> IO.sleep(1.second)
      _ <- latch.release
      _ <- IO("4...").debugs >> IO.sleep(1.second)
      _ <- latch.release
      _ <- IO("3...").debugs >> IO.sleep(1.second)
      _ <- latch.release
      _ <- IO("2...").debugs >> IO.sleep(1.second)
      _ <- latch.release
      _ <- IO("1...").debugs >> IO.sleep(1.second)
      _ <- latch.release // gun firing
      _ <- IO("GO GO GO!").debugs
    } yield ()

  def createRunner(id: Int, latch: CountDownLatch[IO]): IO[Unit] =
    for {
      _ <- IO(s"[runner $id] waiting for signal....").debugs
      _ <- latch.await // block this fiber until the count reaches 0
      _ <- IO(s"[runner $id] RUNNING!").debugs
    } yield ()

  def sprint(): IO[Unit] =
    for {
      latch        <- CountDownLatch[IO](5)
      announcerFib <- announcer(latch).start
      _            <- (1 to 10).toList.parTraverse(id => createRunner(id, latch))
      _            <- announcerFib.join
    } yield ()

  /**
   * Exercises: simulate a file downloader on multiple threads
   */
  object FileServer {
    val fileChunksList = Array(
      "I love Scala.\n",
      "Cats Effect seems quite fun.\n",
      "Never would I have thought I would do low-level concurrency WITH pure FP.\n"
    )

    def getNumChunks: IO[Int]            = IO(fileChunksList.length)
    def getFileChunk(n: Int): IO[String] = IO(fileChunksList(n))
  }

  def writeToFile(path: String, contents: String): IO[Unit] = {
    val fileResource = Resource.make(IO(new FileWriter(new File(path))))(writer => IO(writer.close()))
    fileResource.use { writer =>
      IO(writer.write(contents))
    }
  }

  def appendFileContents(fromPath: String, toPath: String): IO[Unit] = {
    val compositeResource = for {
      reader <- Resource.make(IO(Source.fromFile(fromPath)))(source => IO(source.close()))
      writer <- Resource.make(IO(new FileWriter(new File(toPath), true)))(writer => IO(writer.close()))
    } yield (reader, writer)

    compositeResource.use { case (reader, writer) =>
      IO(reader.getLines().foreach(writer.write))
    }
  }

  /*
    - call file server API and get the number of chunks (n)
    - start a CDLatch
    - start n fibers which download a chunk of the file (use the file server's download chunk API)
    - block on the latch until each task has finished
    - after all chunks are done, stitch the files together under the same file on disk
   */
  def getFileName(nChunk: Int): String = s"src/main/resources/chunk-$nChunk.txt"

  def downloadFile(filename: String, destFolder: String): IO[Unit] = {
    for {
      numChunks <- FileServer.getNumChunks
      latch     <- CountDownLatch[IO](numChunks)
      _ <- (0 until numChunks).toList.parTraverse { nChunk =>
        for {
          contents <- FileServer.getFileChunk(nChunk)
          _        <- writeToFile(getFileName(nChunk), contents)
          _        <- latch.release
        } yield ()
      }
      _ <- latch.await
      _ <- (0 until numChunks).toList.traverse { nChunk =>
        appendFileContents(getFileName(nChunk), s"$destFolder/$filename")
      }
    } yield ()
  }

  override def run: IO[Unit] = downloadFile("all_chunks.txt", "src/main/resources")
}
