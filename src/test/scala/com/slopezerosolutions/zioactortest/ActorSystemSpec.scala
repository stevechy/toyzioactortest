package com.slopezerosolutions.zioactortest

import zio._
import zio.test._

class ActorSystemSpec extends zio.test.junit.JUnitRunnableSpec {

  def spec: Spec[Any, Throwable] = suite("ActorSystem tests")(
    test("can send a message") {
      val actorSystem = new ActorSystem

      val sendMessageZIO = for{
        resultPromise <- Promise.make[Throwable, String]
        destination <- ZIO.succeed(actorSystem.promiseMessageDestination(resultPromise))
        _ <- actorSystem.send("Hello world", destination)
        result <- resultPromise.await
      } yield result
      assertZIO(sendMessageZIO)(Assertion.equalTo("Hello world"))
    },
    test("can send a message to an adapted destination") {
      val actorSystem = new ActorSystem

      val sendMessageZIO = for {
        resultPromise <- Promise.make[Throwable, Int]
        destination <- ZIO.succeed(actorSystem.promiseMessageDestination(resultPromise))
        adapterDestination <- ZIO.succeed(actorSystem.adaptedMessageDestination(
          (stringValue:String) => stringValue.length,
          destination))
        _ <- actorSystem.send("Hello world", adapterDestination)
        result <- resultPromise.await
      } yield result
      assertZIO(sendMessageZIO)(Assertion.equalTo(11))
    }
  )
}
