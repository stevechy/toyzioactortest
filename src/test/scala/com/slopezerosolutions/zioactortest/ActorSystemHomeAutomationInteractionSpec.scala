package com.slopezerosolutions.zioactortest

import zio.*
import zio.stm.{STM, TMap}
import zio.test.*

class ActorSystemHomeAutomationInteractionSpec extends zio.test.junit.JUnitRunnableSpec {
  case class HomeAutomationDirectory(homeSupervisor: Option[MessageDestination[HouseSupervisorMessage]],
                                     houseRoutes: Option[HouseRoutes])

  case class HouseRoutes(routes: TMap[String, MessageDestination[HouseCommand]], temperatureSensorRoutes: TemperatureSensorRoutes) {
    def houseActorOption(houseId: String): Task[Option[MessageDestination[HouseCommand]]] =
      STM.atomically(routes.get(houseId))
  }

  case class TemperatureSensorRoutes(routes: TMap[String, MessageDestination[TemperatureSensorCommand]]) {
    def temperatureActorOption(sensorId: String): Task[Option[MessageDestination[TemperatureSensorCommand]]] =
      STM.atomically(routes.get(sensorId))
  }

  case class House(houseId: String)

  case class TemperatureSensor(houseId: String, sensorId: String)

  abstract sealed class HouseSupervisorMessage

  case class ActivateHouse(houseId: String, replyTo: MessageDestination[Option[MessageDestination[HouseCommand]]]) extends HouseSupervisorMessage

  case class HouseSummary()

  abstract sealed class HouseCommand

  case class ActivateTemperatureSensor(temperatureSensorId: String, replyTo: MessageDestination[Option[MessageDestination[TemperatureSensorCommand]]]) extends HouseCommand

  case class GetHouseSummary(replyTo: MessageDestination[HouseSummary]) extends HouseCommand

  abstract sealed class TemperatureSensorCommand

  case class RecordTemperature(celsius: Double, replyTo: MessageDestination[MaxTemperature]) extends TemperatureSensorCommand

  case class MaxTemperature(celsius: Double)

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
    },
    test("Reactivates a persisted temperature sensor actor and sends it a message") {
      val houseDatabase: List[House] = List(House("123GreenStreet"))
      val temperatureSensorsDatabase: List[TemperatureSensor] = List(TemperatureSensor("123GreenStreet", "MainFloorSensor"))

      val temperatureActorTemplate = ActorTemplate.stateful[TemperatureSensorCommand, Double](() => Double.MinValue, (actorService, message, maxTemp) => message match {
        case RecordTemperature(celsius, maxTempReply) =>
          for {
            newMaxTemp <- ZIO.succeed(Math.max(celsius, maxTemp))
            _ <- maxTempReply.send(MaxTemperature(newMaxTemp))
          } yield StatefulActor.UpdateState(newMaxTemp)
        case _ => ZIO.succeed(StatefulActor.Continue())
      })

      val homeDirectoryInitializer = houseWithSensorsSupervisor(houseDatabase,
        temperatureSensorsDatabase,
        temperatureActorTemplate)
      for {
        actorSystem <- ActorSystem.initialize(HomeAutomationDirectory(None, None), List(
          homeDirectoryInitializer
        ))
        sensorId = "MainFloorSensor"
        temperatureSensorOption <- getOrActivateTemperatureSensor(sensorId, actorSystem, temperatureSensorsDatabase)
        temperatureSensor = temperatureSensorOption.get
        directory <- actorSystem.directory
        _ <- STM.atomically {
          directory.houseRoutes.get.temperatureSensorRoutes.routes.keys
        }.debug
        maxTemp1 <- MessageDestination.promise[MaxTemperature](destination => {
          temperatureSensor.send(RecordTemperature(21, destination))
        }).flatMap(_.await)
        maxTemp2 <- MessageDestination.promise[MaxTemperature](destination => {
          temperatureSensor.send(RecordTemperature(23, destination))
        }).flatMap(_.await)
        maxTemp3 <- MessageDestination.promise[MaxTemperature](destination => {
          temperatureSensor.send(RecordTemperature(20, destination))
        }).flatMap(_.await)
      } yield assertTrue(maxTemp1.celsius == 21 &&
        maxTemp2.celsius == 23 &&
        maxTemp3.celsius == 23)
    },
    test("Stopping the house actor also stops the child temperature sensors") {
      val houseDatabase: List[House] = List(House("123GreenStreet"))
      val temperatureSensorsDatabase: List[TemperatureSensor] = List(TemperatureSensor("123GreenStreet", "MainFloorSensor"))

      val homeDirectoryInitializer = houseWithSensorsInitializer(houseDatabase, temperatureSensorsDatabase)
      for {
        actorSystem <- ActorSystem.initialize(HomeAutomationDirectory(None, None), List(
          homeDirectoryInitializer
        ))
        houseId = "123GreenStreet"
        houseActorOption <- getOrActivateHouse(houseId, actorSystem)
        houseActor = houseActorOption.get
        sensorId = "MainFloorSensor"
        temperatureSensorOption <- getOrActivateTemperatureSensor(sensorId, actorSystem, temperatureSensorsDatabase)
        temperatureSensor = temperatureSensorOption.get
        directory <- actorSystem.directory
        _ <- STM.atomically {
          directory.houseRoutes.get.temperatureSensorRoutes.routes.keys
        }.debug
        maxTemp1 <- MessageDestination.promise[MaxTemperature](destination => {
          temperatureSensor.send(RecordTemperature(21, destination))
        }).flatMap(_.await)
        _ <- actorSystem.stopActor(houseActor)
        sensorActive <- actorSystem.activeActorDestination(temperatureSensor)
      } yield assertTrue(maxTemp1.celsius == 21 && !sensorActive)
    },
    test("Restarting the temperature actor resets its state") {
      val houseDatabase: List[House] = List(House("123GreenStreet"))
      val temperatureSensorsDatabase: List[TemperatureSensor] = List(TemperatureSensor("123GreenStreet", "MainFloorSensor"))

      val homeDirectoryInitializer = houseWithSensorsInitializer(houseDatabase, temperatureSensorsDatabase)
      for {
        actorSystem <- ActorSystem.initialize(HomeAutomationDirectory(None, None), List(
          homeDirectoryInitializer
        ))
        sensorId = "MainFloorSensor"
        temperatureSensorOption <- getOrActivateTemperatureSensor(sensorId, actorSystem, temperatureSensorsDatabase)
        temperatureSensor = temperatureSensorOption.get
        directory <- actorSystem.directory
        _ <- STM.atomically {
          directory.houseRoutes.get.temperatureSensorRoutes.routes.keys
        }
        maxTemp1 <- MessageDestination.promise[MaxTemperature](destination => {
          temperatureSensor.send(RecordTemperature(25, destination))
        }).flatMap(_.await)
        maxTemp2 <- MessageDestination.promise[MaxTemperature](destination => {
          temperatureSensor.send(RecordTemperature(21, destination))
        }).flatMap(_.await)
        _ <- actorSystem.restartActor(temperatureSensor)
        maxTempAfterRestart <- MessageDestination.promise[MaxTemperature](destination => {
          temperatureSensor.send(RecordTemperature(10, destination))
        }).flatMap(_.await)
      } yield assertTrue(maxTemp1.celsius == 25 &&
        maxTemp2.celsius == 25 &&
        maxTempAfterRestart.celsius == 10
      )
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

  private def getOrActivateTemperatureSensor(temperatureSensorId: String,
                                             actorSystem: ActorSystem[HomeAutomationDirectory],
                                             temperatureSensorDatabase: List[TemperatureSensor]): Task[Option[MessageDestination[TemperatureSensorCommand]]] = {
    for {
      directory <- actorSystem.directory
      maybeTemperatureSensorRoutes = directory.houseRoutes
        .map(_.temperatureSensorRoutes)
      temperatureActorOption <- maybeTemperatureSensorRoutes.get
        .temperatureActorOption(temperatureSensorId)
      temperatureSensorOption <- temperatureActorOption match {
        case Some(temperatureSensor) => ZIO.succeed(Some(temperatureSensor))
        case None => for {
          temperatureSensor <- ZIO.succeed(temperatureSensorDatabase.find(_.sensorId == temperatureSensorId))
          sensorOption <- temperatureSensor match {
            case Some(sensor) => for {
              houseActorOption <- getOrActivateHouse(sensor.houseId, actorSystem)
              sensor <- MessageDestination.promise[Option[MessageDestination[TemperatureSensorCommand]]](destination =>
                houseActorOption.get.send(ActivateTemperatureSensor(temperatureSensorId, destination))
              ).flatMap(_.await)

            } yield sensor
            case None => ZIO.succeed(None)
          }
        } yield sensorOption
      }
    } yield temperatureSensorOption
  }

  private def basicHouseSupervisor(houseActor: ActorTemplate[HouseCommand], houseDatabase: List[House]) = {
    new ActorInitializer[HomeAutomationDirectory] {
      override type MessageType = HouseSupervisorMessage

      override def initialize: Task[(ActorTemplate[HouseSupervisorMessage], (MessageDestination[HouseSupervisorMessage], HomeAutomationDirectory) => Task[HomeAutomationDirectory])] = {
        for {
          temperatureSensorMap <- STM.atomically(TMap.empty[String, MessageDestination[TemperatureSensorCommand]])
          temperatureSensorRoutes = TemperatureSensorRoutes(temperatureSensorMap)
          routes <- STM.atomically(TMap.empty[String, MessageDestination[HouseCommand]])
          houseRouteObject = HouseRoutes(routes, temperatureSensorRoutes)
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

  private def houseWithSensorsInitializer(houseDatabase: List[House],
                                          temperatureSensorsDatabase: List[TemperatureSensor]) = {
    houseWithSensorsSupervisor(houseDatabase,
      temperatureSensorsDatabase,
      temperatureActorTemplate)
  }

  private def temperatureActorTemplate = {
    val temperatureActorTemplate = ActorTemplate.stateful[TemperatureSensorCommand, Double](() => Double.MinValue, (actorService, message, maxTemp) => message match {
      case RecordTemperature(celsius, maxTempReply) =>
        for {
          newMaxTemp <- ZIO.succeed(Math.max(celsius, maxTemp))
          _ <- maxTempReply.send(MaxTemperature(newMaxTemp))
        } yield StatefulActor.UpdateState(newMaxTemp)
      case _ => ZIO.succeed(StatefulActor.Continue())
    })
    temperatureActorTemplate
  }

  private def houseWithSensorsSupervisor(houseDatabase: List[House],
                                         temperatureSensorsDatabase: List[TemperatureSensor],
                                         temperatureSensorTemplate: ActorTemplate[TemperatureSensorCommand]) = {
    val houseActorTemplate = (houseRoutes: HouseRoutes) => ActorTemplate.handler((actorService: ActorService, houseCommand: HouseCommand) => houseCommand match {
      case GetHouseSummary(replyTo) => replyTo.send(HouseSummary())
      case ActivateTemperatureSensor(temperatureSensorId, replyTo) => for {
        temperatureSensorOption <- ZIO.succeed(temperatureSensorsDatabase.find(_.sensorId == temperatureSensorId))
        _ <- if (temperatureSensorOption.isEmpty)
          replyTo.send(None)
        else
          for {
            temperatureActor <- actorService.startActor(temperatureSensorTemplate)
            _ <- STM.atomically(houseRoutes.temperatureSensorRoutes.routes.put(temperatureSensorId, temperatureActor))
            _ <- replyTo.send(Some(temperatureActor))
          } yield ()
      } yield true
    })
    new ActorInitializer[HomeAutomationDirectory] {
      override type MessageType = HouseSupervisorMessage

      override def initialize: Task[(ActorTemplate[HouseSupervisorMessage], (MessageDestination[HouseSupervisorMessage], HomeAutomationDirectory) => Task[HomeAutomationDirectory])] = {
        for {
          temperatureSensorMap <- STM.atomically(TMap.empty[String, MessageDestination[TemperatureSensorCommand]])
          temperatureSensorRoutes = TemperatureSensorRoutes(temperatureSensorMap)
          routes <- STM.atomically(TMap.empty[String, MessageDestination[HouseCommand]])
          houseRouteObject = HouseRoutes(routes, temperatureSensorRoutes)
          value = ActorTemplate.stateful(() => houseRouteObject,
            (actorService: ActorService, message: HouseSupervisorMessage, houseRoutes: HouseRoutes) => {
              message match {
                case ActivateHouse(houseId, replyTo) => for {
                  lookupHouse <- ZIO.succeed(houseDatabase.find(_.houseId == houseId))
                  _ <- lookupHouse match {
                    case Some(house) =>
                      for {
                        houseActor <- actorService.startActor(houseActorTemplate(houseRoutes))
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
