val scala3Version = "3.3.7"
val scalusVersion = "0.14.1"
ThisBuild / scalaVersion := scala3Version
autoCompilerPlugins := true

name := "cosmex"

version := "0.2.0-SNAPSHOT"

scalaVersion := scala3Version

Global / onChangedBuildSource := ReloadOnSourceChanges

resolvers += Resolver.sonatypeCentralSnapshots

addCompilerPlugin("org.scalus" %% "scalus-plugin" % scalusVersion)

scalacOptions += "-Wunused:all"
scalacOptions += "-deprecation"
scalacOptions += "-feature"

libraryDependencies += "org.scalus" %% "scalus" % scalusVersion
libraryDependencies += "org.scalus" %% "scalus-cardano-ledger" % scalusVersion
libraryDependencies += "org.scalus" %% "scalus-bloxbean-cardano-client-lib" % scalusVersion
libraryDependencies += "org.scalus" %% "scalus-testkit" % scalusVersion % Test
libraryDependencies += "com.bloxbean.cardano" % "cardano-client-lib" % "0.7.0"
libraryDependencies ++= Seq(
  // Tapir for API definition
  "com.softwaremill.sttp.tapir" %% "tapir-netty-server-sync" % "1.12.4",
  "com.softwaremill.sttp.tapir" %% "tapir-swagger-ui-bundle" % "1.12.4",
  // Ox for structured concurrency (needed by Netty server)
  "com.softwaremill.ox" %% "core" % "1.0.2",
  // JSON serialization with ujson
  "com.lihaoyi" %% "ujson" % "4.4.1",
  // Argument parsing
  "com.monovore" %% "decline" % "2.5.0",
  "org.slf4j" % "slf4j-simple" % "2.0.17",
  // Configuration
  "com.typesafe" % "config" % "1.4.5"
)

libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.19" % "test"
libraryDependencies += "org.scalatestplus" %% "scalacheck-1-18" % "3.2.19.0" % "test"
libraryDependencies += "com.lihaoyi" %% "pprint" % "0.9.5" % "test"
libraryDependencies += "com.lihaoyi" %% "requests" % "0.9.0" % "test" // HTTP client for Blockfrost
libraryDependencies ++= Seq(
  "com.softwaremill.sttp.client4" %% "core" % "4.0.13",
  "com.softwaremill.sttp.client4" %% "ox" % "4.0.13"
)

// Yaci DevKit for local blockchain testing
libraryDependencies ++= Seq(
  "com.bloxbean.cardano" % "yaci-cardano-test" % "0.1.0" % Test,
  "com.bloxbean.cardano" % "cardano-client-backend" % "0.7.0" % Test,
  "com.bloxbean.cardano" % "cardano-client-backend-blockfrost" % "0.7.0" % Test,
  "org.testcontainers" % "testcontainers" % "2.0.2" % Test
)

// Test configuration
Test / parallelExecution := false // Required for Yaci DevKit testcontainers
Test / javaOptions ++= Seq("-Xmx2g") // Increase memory for blockchain tests
Test / classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.Flat // Fix classloading for yaci-devkit

addCommandAlias(
  "quick",
  "scalafmtAll;scalafmtSbt;testQuick"
)

addCommandAlias(
  "ci",
  "clean;scalafmtCheckAll;scalafmtSbtCheck;Test/compile;test"
)
