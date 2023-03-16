package com.slopezerosolutions.zioactortest

import zio.*
import zio.test.*

class ActorSystemInteractionSpec extends zio.test.junit.JUnitRunnableSpec {
  case class PingMessage(replyTo: MessageDestination[String])

  abstract sealed class BlackjackSupervisorMessage

  case class StartBlackJackGameMessage(replyTo: MessageDestination[BlackjackSupervisorMessage]) extends BlackjackSupervisorMessage

  case class StartedBlackJackGameMessage(gameActorMessageDestination: MessageDestination[BlackjackGameMessage]) extends BlackjackSupervisorMessage

  case class Card(suit: String, rank: String)

  abstract sealed class BlackjackGameMessage

  case class ShowHand(replyTo: MessageDestination[BlackjackGameMessage]) extends BlackjackGameMessage

  case class Hand(hand: List[Card]) extends BlackjackGameMessage

  case class PokerSupervisorMessage(message: String, replyTo: MessageDestination[String])

  case class GameDirectory(blackjackSupervisor: Option[MessageDestination[BlackjackSupervisorMessage]],
                           pokerSupervisor: Option[MessageDestination[PokerSupervisorMessage]])

  def spec: Spec[Any, Throwable] = suite("ActorSystem interaction tests")(
    suite("Blackjack Game")(
      test("Can create a game actor by sending a message to the supervisor") {
        val initializeZIO = ActorSystem.initialize(GameDirectory(None, None), List(
          blackjackSupervisor(noResponseGameActor),
        ))
        val testZIO = for {
          actorSystem <- initializeZIO
          startGamePromise <- actorSystem.withPromiseMessageDestination[String]((destination) => {
            val handler = (message: BlackjackSupervisorMessage) => message match {
              case StartedBlackJackGameMessage(_) => "Started game"
              case _ => "Not started"
            }
            val adaptedDestination = actorSystem.adaptedMessageDestination(
              handler,
              destination)
            for {
              directory <- actorSystem.directory
              _ <- actorSystem.send(StartBlackJackGameMessage(adaptedDestination), directory.blackjackSupervisor.get)
            } yield ()
          })
          result <- startGamePromise.await
        } yield result
        assertZIO(testZIO)(Assertion.equalTo("Started game"))
      },
      test("Can send messages to the game actor created by the supervisor") {
        val initializeZIO = ActorSystem.initialize(GameDirectory(None, None), List(
          blackjackSupervisor(staticHandGameActor),
        ))
        val testZIO = for {
          actorSystem <- initializeZIO
          directory <- actorSystem.directory
          gameStarted <- actorSystem.withPromiseMessageDestination[BlackjackSupervisorMessage]((destination) => {
            for {
              _ <- actorSystem.send(StartBlackJackGameMessage(destination), directory.blackjackSupervisor.get)
            } yield ()
          })
          result <- gameStarted.await
          gameActorMessageDestination = result match {
            case StartedBlackJackGameMessage(game) => Some(game)
            case _ => None
          }
          gameReply <- actorSystem.withPromiseMessageDestination[BlackjackGameMessage]((destination) => {
            for {
              _ <- actorSystem.send(ShowHand(destination), gameActorMessageDestination.get)
            } yield ()
          })
          hand <- gameReply.await
        } yield hand
        assertZIO(testZIO)(Assertion.equalTo(Hand(
          hand = List(Card(
            suit = "Hearts",
            rank = "A"
          ), Card(
            suit = "Hearts",
            rank = "10"
          ))
        )))
      }
    )
  )

  private def noResponseGameActor = {
    ActorTemplate.handler((actorSystem, message: BlackjackGameMessage) => message match {
      case _ => ZIO.succeed(true)
    })
  }

  private def staticHandGameActor = {
    ActorTemplate.handler((actorSystem, message: BlackjackGameMessage) => message match {
      case ShowHand(replyTo) => for {
        _ <- actorSystem.send(Hand(List(Card("Hearts", "A"), Card("Hearts", "10"))), replyTo)
      } yield true
      case _ => ZIO.succeed(true)
    })
  }


  private def blackjackSupervisor(gameHandler: ActorTemplate[BlackjackGameMessage]) = {
    new ActorInitializer[GameDirectory] {
      override type MessageType = BlackjackSupervisorMessage

      override def actorTemplate: Task[ActorTemplate[BlackjackSupervisorMessage]] = {
        val value = ActorTemplate.handler((actorService: ActorService,
                                           message: BlackjackSupervisorMessage) =>
          message match {
            case StartBlackJackGameMessage(replyTo) =>
              for {
                blackjackActor <- actorService.startActor(gameHandler)
                _ <- actorService.send(StartedBlackJackGameMessage(blackjackActor), replyTo)
              } yield true
            case _ => ZIO.succeed(true)
          })
        ZIO.succeed(value)
      }

      override def injectActorReference(messageDestination: MessageDestination[BlackjackSupervisorMessage],
                                        directory: GameDirectory): GameDirectory = {
        directory.copy(blackjackSupervisor = Some(messageDestination))
      }
    }
  }
}