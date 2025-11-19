val scala3Version = "3.3.7"
val scalusVersion = "0.13.0+295-48d3dee4-SNAPSHOT"
ThisBuild / scalaVersion := scala3Version
autoCompilerPlugins := true

name := "cosmex"

version := "0.1.0-SNAPSHOT"

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
  "com.softwaremill.sttp.tapir" %% "tapir-netty-server-sync" % "1.12.3",
  "com.softwaremill.sttp.tapir" %% "tapir-swagger-ui-bundle" % "1.12.3",
  // Ox for structured concurrency (needed by Netty server)
  "com.softwaremill.ox" %% "core" % "1.0.1",
  // JSON serialization with jsoniter-scala
  "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % "2.38.4",
  "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % "2.38.4" % "compile-internal",
  // Argument parsing
  "com.monovore" %% "decline" % "2.5.0",
  "org.slf4j" % "slf4j-simple" % "2.0.17"
)

libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.19" % "test"
libraryDependencies += "org.scalatestplus" %% "scalacheck-1-18" % "3.2.19.0" % "test"
libraryDependencies += "com.lihaoyi" %% "pprint" % "0.9.4" % "test"
libraryDependencies ++= Seq(
  "com.softwaremill.sttp.client3" %% "core" % "3.11.0" % "test"
)

addCommandAlias(
  "quick",
  "scalafmtAll;scalafmtSbt;testQuick"
)

addCommandAlias(
  "ci",
  "clean;scalafmtCheckAll;scalafmtSbtCheck;Test/compile;test"
)
