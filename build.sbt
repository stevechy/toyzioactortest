scalaVersion := "3.1.3"
organization := "com.slopezerosolutions.com"
name := "zio-actor-test"

lazy val zioVersion = "2.0.10"

libraryDependencies ++= Seq(
  "dev.zio" %% "zio" % zioVersion,
  "dev.zio" %% "zio-test" % zioVersion,
  "dev.zio" %% "zio-test-sbt" % zioVersion,
  "dev.zio" %% "zio-test-junit" % zioVersion,
)
