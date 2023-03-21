package com.slopezerosolutions.zioactortest

import zio.*
import zio.stm.{STM, TMap}
import zio.test.*

class ActorSystemHomeAutomationInteractionSpec extends zio.test.junit.JUnitRunnableSpec {
  case class HomeAutomationDirectory(homeSupervisor: Option[MessageDestination[HouseSupervisorMessage]],
                                     houseRoutes: Option[HouseRoutes])

  case class HouseRoutes(routes: TMap[String, MessageDestination[HouseCommand]]) {
    def houseActorOption(houseId: String): Task[Option[MessageDestination[HouseCommand]]] =
      STM.atomically(routes.get(houseId))
  }

  case class House(houseId: String)

  abstract sealed class HouseSupervisorMessage

  case class ActivateHouse(houseId: String, replyTo: MessageDestination[Option[MessageDestination[HouseCommand]]]) extends HouseSupervisorMessage

  case class HouseSummary()

  abstract sealed class HouseCommand
  case class ActivateTemperatureSensor(temperatureSensorId: String)

  case class GetHouseSummary(replyTo: MessageDestination[HouseSummary]) extends HouseCommand

  abstract sealed class TemperatureSensorCommand
  case class RecordTemperature(celsius: Double)

  def spec: Spec[Any, Throwable] = suite("ActorSystem Home Automation Interaction tests")(
    test("Reactivates a persisted house actor") {
      val houseDatabase: List[House] = List(House("123GreenStreet"))
      val houseActor = ActorTemplate.handler((houseCommand: HouseCommand) => ZIO.succeed(true))
      val homeDirectoryInitializer = basicHouseSupervisor(houseActor, houseDatabase)
      for {
        actorSystem <- ActorSystem.initialize(HomeAutomationDirectory(None, None), List(
          homeDirectoryInitializer
        ))
        directory <- actorSystem.directory
        houseId = "123GreenStreet"
        houseActorOption <- directory.houseRoutes.get.houseActorOption(houseId)
        houseActorActivateOption <- houseActorOption match {
          case Some(houseActor) => ZIO.succeed(Some(houseActor))
          case None => MessageDestination.promise[Option[MessageDestination[HouseCommand]]](destination =>
            directory.homeSupervisor.get.send(ActivateHouse(houseId, destination))
          ).flatMap(_.await)
        }
      } yield assertTrue(houseActorOption.isEmpty && houseActorActivateOption.isDefined)
    },
    test("Reactivates a persisted house actor and sends it a message") {
      val houseDatabase: List[House] = List(House("123GreenStreet"))
      val houseActor = ActorTemplate.handler((houseCommand: HouseCommand) => houseCommand match {
        case GetHouseSummary(replyTo) => replyTo.send(HouseSummary())
        case _ => ZIO.succeed(true)
      })
      val homeDirectoryInitializer = basicHouseSupervisor(houseActor, houseDatabase)
      for {
        actorSystem <- ActorSystem.initialize(HomeAutomationDirectory(None, None), List(
          homeDirectoryInitializer
        ))
        houseId = "123GreenStreet"
        houseActorOption <- getOrActivateHouse(houseId, actorSystem)
        houseSummary <- MessageDestination.promise[HouseSummary](destination => {
          houseActorOption.get.send(GetHouseSummary(destination))
        }).flatMap(_.await)
      } yield assertTrue(houseSummary == HouseSummary())
    }
  )

  private def getOrActivateHouse(houseId: String, actorSystem: ActorSystem[HomeAutomationDirectory]): Task[Option[MessageDestination[HouseCommand]]] = {
    for {
      directory <- actorSystem.directory
      houseActorOption <- directory.houseRoutes.get.houseActorOption(houseId)
      houseActorActivateOption <- houseActorOption match {
        case Some(houseActor) => ZIO.succeed(Some(houseActor))
        case None => MessageDestination.promise[Option[MessageDestination[HouseCommand]]](destination =>
          directory.homeSupervisor.get.send(ActivateHouse(houseId, destination))
        ).flatMap(_.await)
      }
    } yield houseActorActivateOption
  }

  private def basicHouseSupervisor(houseActor: ActorTemplate[HouseCommand], houseDatabase: List[House]) = {
    new ActorInitializer[HomeAutomationDirectory] {
      override type MessageType = HouseSupervisorMessage

      override def initialize: Task[(ActorTemplate[HouseSupervisorMessage], (MessageDestination[HouseSupervisorMessage], HomeAutomationDirectory) => Task[HomeAutomationDirectory])] = {
        for {
          routes <- STM.atomically(TMap.empty[String, MessageDestination[HouseCommand]])
          houseRouteObject = HouseRoutes(routes)
          value = ActorTemplate.stateful(() => houseRouteObject,
            (actorService: ActorService, message: HouseSupervisorMessage, houseRoutes: HouseRoutes) => {
              message match {
                case ActivateHouse(houseId, replyTo) => for {
                  lookupHouse <- ZIO.succeed(houseDatabase.find(_.houseId == houseId))
                  _ <- lookupHouse match {
                    case Some(house) =>
                      for {
                        houseActor <- actorService.startActor(houseActor)
                        _ <- STM.atomically(routes.put(houseId, houseActor))
                        _ <- replyTo.send(Some(houseActor))
                      } yield true
                    case None => replyTo.send(None)
                  }

                } yield StatefulActor.Continue()
              }
            }
          )
        } yield (value, (houseSupervisor, directory) => ZIO.succeed(directory.copy(
          homeSupervisor = Some(houseSupervisor),
          houseRoutes = Some(houseRouteObject)
        )))
      }
    }
  }
}
