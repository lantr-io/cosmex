package cosmex

import scalus.*
import scalus.builtin.Data.toData
import scalus.uplc.Program

object CosmexContract {
    given Compiler.Options = Compiler.Options(
      targetLoweringBackend = Compiler.TargetLoweringBackend.SumOfProductsLowering
    )
    private val compiledValidator = Compiler.compile(CosmexValidator.validate)

    def mkCosmexProgram(params: ExchangeParams, generateErrorTraces: Boolean = false): Program = {
        val program =
            compiledValidator.toUplcOptimized(generateErrorTraces = generateErrorTraces).plutusV3
        val uplcProgram = program $ params.toData
        uplcProgram
    }
}
