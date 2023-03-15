package com.slopezerosolutions.zioactortest

import zio.*
import zio.test.*

class ActorSystemSpec extends zio.test.junit.JUnitRunnableSpec {
  case class PingMessage(replyTo: MessageDestination[String])

  case class BlackjackSupervisorMessage(message: String, replyTo: MessageDestination[String])

  case class PokerSupervisorMessage(message: String, replyTo: MessageDestination[String])

  case class GameDirectory(blackjackSupervisor: Option[MessageDestination[BlackjackSupervisorMessage]],
                           pokerSupervisor: Option[MessageDestination[PokerSupervisorMessage]])

  def spec: Spec[Any, Throwable] = suite("ActorSystem tests")(
    test("can send a message") {
      val sendMessageZIO = for {
        actorSystem <- ActorSystem.initialize
        resultPromise <- Promise.make[Throwable, String]
        destination <- ZIO.succeed(actorSystem.promiseMessageDestination(resultPromise))
        _ <- actorSystem.send("Hello world", destination)
        result <- resultPromise.await
      } yield result
      assertZIO(sendMessageZIO)(Assertion.equalTo("Hello world"))
    },
    test("can send a message to an adapted destination") {
      val sendMessageZIO = for {
        actorSystem <- ActorSystem.initialize
        resultPromise <- Promise.make[Throwable, Int]
        destination <- ZIO.succeed(actorSystem.promiseMessageDestination(resultPromise))
        adapterDestination <- ZIO.succeed(actorSystem.adaptedMessageDestination(
          (stringValue: String) => stringValue.length,
          destination))
        _ <- actorSystem.send("Hello world", adapterDestination)
        result <- resultPromise.await
      } yield result
      assertZIO(sendMessageZIO)(Assertion.equalTo(11))
    },
    suite("Actor tests")(
      test("Can send a message to an actor") {
        val sendMessageZIO = for {
          actorSystem <- ActorSystem.initialize
          actorMessageDestination <- actorSystem.startActor((string: String) => ZIO.succeed(true))
          result <- actorSystem.send("Hello world", actorMessageDestination)
        } yield result
        assertZIO(sendMessageZIO)(Assertion.equalTo(true))
      },
      test("Can send a message to an actor and receive a reply") {
        val sendMessageZIO = for {
          actorSystem <- ActorSystem.initialize
          actorMessageDestination <- actorSystem.startActor((pingMessage: PingMessage) => for {
            result <- actorSystem.send("Pong!", pingMessage.replyTo)
          } yield result)
          resultPromise <- Promise.make[Throwable, String]
          destination <- ZIO.succeed(actorSystem.promiseMessageDestination(resultPromise))
          result <- actorSystem.send(PingMessage(destination), actorMessageDestination)
          promiseResult <- resultPromise.await
        } yield promiseResult
        assertZIO(sendMessageZIO)(Assertion.equalTo("Pong!"))
      }
    ),
    suite("Actor Initializers")(
      test("Creates a fixed set of actors") {
        val initializeZIO = ActorSystem.initialize(GameDirectory(None, None), List(
          new ActorInitializer[GameDirectory] {
            override type MessageType = BlackjackSupervisorMessage

            override def actorTemplate: Task[ActorTemplate[BlackjackSupervisorMessage]] = {
              val value = HandlerActorTemplate((message: MessageType) => ZIO.succeed(true))
              ZIO.succeed(value)
            }

            override def injectActorReference(messageDestination: MessageDestination[BlackjackSupervisorMessage], directory: GameDirectory): GameDirectory = {
              directory.copy(blackjackSupervisor = Some(messageDestination))
            }
          },
          new ActorInitializer[GameDirectory]{
            override type MessageType = PokerSupervisorMessage

            override def actorTemplate: Task[ActorTemplate[PokerSupervisorMessage]] = {
              val value = HandlerActorTemplate((message: MessageType) => ZIO.succeed(true))
              ZIO.succeed(value)
            }

            override def injectActorReference(messageDestination: MessageDestination[PokerSupervisorMessage], directory: GameDirectory): GameDirectory = {
              directory.copy(pokerSupervisor = Some(messageDestination))
            }
          }
        ))
        val testZIO = for {
          actorSystem <- initializeZIO
          resultPromise <- Promise.make[Throwable, String]
          destination <- ZIO.succeed(actorSystem.promiseMessageDestination(resultPromise))
          _ <- actorSystem.send(BlackjackSupervisorMessage("Hello", replyTo = destination),
            actorSystem.directory.blackjackSupervisor.get)
        } yield true
        assertZIO(testZIO)(Assertion.equalTo(true))
      }
    )
  )
}
