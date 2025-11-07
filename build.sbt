val scala3Version = "3.3.7"
val scalusVersion = "0.13.0+165-e5adac74-SNAPSHOT"
ThisBuild / scalaVersion := scala3Version
autoCompilerPlugins := true

name := "scalus"

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

libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.19" % "test"
libraryDependencies += "org.scalatestplus" %% "scalacheck-1-18" % "3.2.19.0" % "test"
libraryDependencies += "com.lihaoyi" %% "pprint" % "0.9.4" % "test"
