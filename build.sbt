import Dependencies._

ThisBuild / scalaVersion := "2.13.2"
ThisBuild / version := IO.read(file("version"))
ThisBuild / organization := "org.zella"
ThisBuild / organizationName := "zella"

val Http4sVersion = "0.21.7"

val mainDependencies = Seq(
  scalaTest % Test,
  "com.google.cloud" % "google-cloud-pubsub" % "1.108.1",
  "co.fs2" %% "fs2-core" % "2.4.4",
  "co.fs2" %% "fs2-io" % "2.4.4",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2",
  "io.circe" %% "circe-generic" % "0.13.0",
  "io.circe" %% "circe-parser" % "0.13.0",
  "org.http4s" %% "http4s-circe" % Http4sVersion,
  "org.http4s" %% "http4s-dsl" % Http4sVersion,
  "org.http4s" %% "http4s-blaze-client" % Http4sVersion,
  "ch.qos.logback" % "logback-classic" % "1.2.3"
)

assemblyMergeStrategy in assembly := {
  case "module-info.class" => MergeStrategy.first
  case x =>
    val oldStrategy = (assemblyMergeStrategy in assembly).value
    oldStrategy(x)
}

assemblyOutputPath in assembly := file("build/assembly.jar")

lazy val root = (project in file("."))
  .settings(
    name := "http-from-pubsub",
    libraryDependencies ++= mainDependencies,
    mainClass in assembly := Some("org.zella.frompubsub.Main"),
  )
