package cosmex
import com.bloxbean.cardano.client.common.ADAConversionUtil
import com.bloxbean.cardano.client.plutus.spec.ExUnits
import com.bloxbean.cardano.client.plutus.spec.PlutusV2Script
import com.bloxbean.cardano.client.plutus.spec.Redeemer as PlutusRedeemer
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag
import com.bloxbean.cardano.client.transaction.spec
import com.bloxbean.cardano.client.transaction.spec.Transaction
import com.bloxbean.cardano.client.transaction.spec.TransactionBody
import com.bloxbean.cardano.client.transaction.spec.TransactionInput
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet
import scalus.bloxbean.Interop
import scalus.bloxbean.SlotConfig
import scalus.builtin.ByteString
import scalus.builtin.ByteString.given
import scalus.builtin.Data
import scalus.ledger.api.v2.PubKeyHash
import scalus.ledger.api.v2.ScriptContext

import java.math.BigInteger
import java.util

class TxBuilder(val exchangeParams: ExchangeParams) {
    val cosmexValidator = CosmexValidator.mkCosmexValidator(exchangeParams)

    def mkTx(datum: Data, redeemer: Data, signatories: Seq[PubKeyHash]): Transaction = {
        import scala.jdk.CollectionConverters.*
        val cosmexPlutusScript = PlutusV2Script
            .builder()
            .`type`("PlutusScriptV2")
            .cborHex(cosmexValidator.doubleCborHex)
            .build()
            .asInstanceOf[PlutusV2Script]

        val rdmr = PlutusRedeemer
            .builder()
            .tag(RedeemerTag.Spend)
            .data(Interop.toPlutusData(redeemer))
            .index(BigInteger.valueOf(0))
            .exUnits(
              ExUnits
                  .builder()
                  .steps(BigInteger.valueOf(1000))
                  .mem(BigInteger.valueOf(1000))
                  .build()
            )
            .build()

        val input = TransactionInput
            .builder()
            .transactionId("1ab6879fc08345f51dc9571ac4f530bf8673e0d798758c470f9af6f98e2f3982")
            .index(0)
            .build()
        val inputs = util.List.of(input)

        val cosmexTxOut = TransactionOutput
            .builder()
            .value(spec.Value.builder().coin(BigInteger.valueOf(20)).build())
            .inlineDatum(Interop.toPlutusData(datum))
            .address(
              "addr1q8q7jyap76l0d5gqj8naw5t49yu3f0h7qkzsps9z0gfjcu25uj747vu83mvg3fuh6ttdgwshjwtcne6esrpct2uzmnuqdqd82j"
            )

        val tx = Transaction
            .builder()
            .body(
              TransactionBody
                  .builder()
                  .fee(ADAConversionUtil.adaToLovelace(0.2))
                  .inputs(inputs)
                  .outputs(util.List.of(cosmexTxOut.build()))
                  .validityStartInterval(10)
                  .ttl(1000)
                  .requiredSigners(signatories.map(_.hash.bytes).asJava)
                  .build()
            )
            .witnessSet(
              TransactionWitnessSet
                  .builder()
                  .plutusV2Scripts(util.List.of(cosmexPlutusScript))
                  .redeemers(util.List.of(rdmr))
                  .build()
            )
            .build()
        tx
    }

    def makeScriptContext(tx: Transaction): ScriptContext = {
        import scala.jdk.CollectionConverters.*
        val utxo = Map(tx.getBody.getInputs.get(0) -> tx.getBody.getOutputs.get(0))
        val purpose = Interop.getScriptPurpose(
          tx.getWitnessSet.getRedeemers.get(0),
          tx.getBody().getInputs(),
          util.List.of(),
          util.List.of(),
          util.List.of()
        )
        val datums = tx.getWitnessSet.getPlutusDataList.asScala.map { plutusData =>
            ByteString.fromArray(plutusData.getDatumHashAsBytes) -> Interop.toScalusData(plutusData)
        }
        val protocolVersion = 8
        val txInfo = Interop.getTxInfoV2(tx, datums, utxo, SlotConfig.default, protocolVersion)
        val scriptContext = ScriptContext(txInfo, purpose)
        scriptContext
    }
}
