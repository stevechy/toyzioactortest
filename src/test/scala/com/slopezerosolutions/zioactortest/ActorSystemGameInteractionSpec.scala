package com.slopezerosolutions.zioactortest

import zio.*
import zio.test.*

class ActorSystemGameInteractionSpec extends zio.test.junit.JUnitRunnableSpec {
  case class PingMessage(replyTo: MessageDestination[String])

  abstract sealed class BlackjackSupervisorMessage

  case class StartBlackJackGameMessage(replyTo: MessageDestination[BlackjackSupervisorMessage]) extends BlackjackSupervisorMessage

  case class StartedBlackJackGameMessage(gameActorMessageDestination: MessageDestination[BlackjackGameMessage]) extends BlackjackSupervisorMessage

  case class Card(suit: String, rank: String)

  case class BlackjackTable(player1Hand: List[Card])

  abstract sealed class BlackjackGameMessage

  case class ShowHand(replyTo: MessageDestination[BlackjackGameMessage]) extends BlackjackGameMessage
  case class Hit(replyTo: MessageDestination[BlackjackGameMessage]) extends BlackjackGameMessage

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
          directory <- actorSystem.directory
          startGamePromise <- MessageDestination.promise[String](destination => {
            val handler = (message: BlackjackSupervisorMessage) => message match {
              case StartedBlackJackGameMessage(_) => "Started game"
              case _ => "Not started"
            }
            val adaptedDestination = actorSystem.adaptedMessageDestination(
              handler,
              destination)
            directory.blackjackSupervisor.get.send(StartBlackJackGameMessage(adaptedDestination))
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
          gameStarted <- startBlackjackGame(directory).flatMap(_.await)
          gameActor = gameStarted.get
          gameReply <- MessageDestination.promise[BlackjackGameMessage](destination => {
            gameActor.send(ShowHand(destination))
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
      },
      test("Can send messages to a stateful game actor created by the supervisor") {
        val initializeZIO = ActorSystem.initialize(GameDirectory(None, None), List(
          blackjackSupervisor(dealerGameActor),
        ))
        val testZIO = for {
          actorSystem <- initializeZIO
          directory <- actorSystem.directory
          gameStarted <- startBlackjackGame(directory).flatMap(_.await)
          gameActor = gameStarted.get
          gameReply1 <- MessageDestination.promise[BlackjackGameMessage](destination => {
            gameActor.send(Hit(destination))
          }).flatMap(_.await)
          gameReply2 <- MessageDestination.promise[BlackjackGameMessage](destination => {
            gameActor.send(Hit(destination))
          }).flatMap(_.await)
          gameReply3 <- MessageDestination.promise[BlackjackGameMessage](destination => {
            gameActor.send(Hit(destination))
          }).flatMap(_.await)
        } yield List(gameReply1,gameReply2, gameReply3)

        val expectedHand1 = Hand(
          hand = List(Card(
            suit = "Hearts",
            rank = "Queen"
          ))
        )
        val expectedHand2 = Hand(
          hand = List(Card(
            suit = "Hearts",
            rank = "Queen"
          ), Card(
            suit = "Hearts",
            rank = "Queen"
          ))
        )
        val expectedHand3 = Hand(
          hand = List(Card(
            suit = "Hearts",
            rank = "Queen"
          ), Card(
            suit = "Hearts",
            rank = "Queen"
          ), Card(
            suit = "Hearts",
            rank = "Queen"
          ))
        )
        assertZIO(testZIO)(Assertion.equalTo(List(expectedHand1,
          expectedHand2,
          expectedHand3)))
      }
    )
  )

  private def startBlackjackGame(directory: GameDirectory) = {
    MessageDestination.promise[Option[MessageDestination[BlackjackGameMessage]]](destination => {
      val gameStartedDestination = destination.adaptedMessageDestination(gameStartedMessageAdapter)
      directory.blackjackSupervisor.get.send(StartBlackJackGameMessage(gameStartedDestination))
    })
  }

  private val gameStartedMessageAdapter = (result: BlackjackSupervisorMessage) => {
    result match {
      case StartedBlackJackGameMessage(game) => Some(game)
      case _ => None
    }
  }

  private def noResponseGameActor = {
    ActorTemplate.handler((message: BlackjackGameMessage) => message match {
      case _ => ZIO.succeed(true)
    })
  }

  private def staticHandGameActor = {
    ActorTemplate.handler((actorSystem, message: BlackjackGameMessage) => message match {
      case ShowHand(replyTo) => for {
        _ <- replyTo.send(Hand(List(Card("Hearts", "A"), Card("Hearts", "10"))))
      } yield true
      case _ => ZIO.succeed(true)
    })
  }

  private def dealerGameActor = {
    ActorTemplate.stateful(()=> BlackjackTable(player1Hand = List()),
      (actorSystem, message: BlackjackGameMessage, table: BlackjackTable) => message match {
      case ShowHand(replyTo) => for {
        _ <- replyTo.send(Hand(table.player1Hand))
      } yield StatefulActor.Continue()
      case Hit(replyTo) => for {
        newTable <- ZIO.attempt {
          table.copy(player1Hand = Card("Hearts", "Queen") +: table.player1Hand)
        }
        _ <- replyTo.send(Hand(newTable.player1Hand))
      } yield StatefulActor.UpdateState(newTable)
      case _ => ZIO.succeed(StatefulActor.Continue())
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
                _ <- replyTo.send(StartedBlackJackGameMessage(blackjackActor))
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
