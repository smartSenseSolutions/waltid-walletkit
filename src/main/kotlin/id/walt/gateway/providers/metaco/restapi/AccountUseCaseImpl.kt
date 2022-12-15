package id.walt.gateway.providers.metaco.restapi

import id.walt.gateway.Common
import id.walt.gateway.dto.*
import id.walt.gateway.dto.trades.TradeListParameter
import id.walt.gateway.providers.metaco.repositories.*
import id.walt.gateway.providers.metaco.restapi.transaction.model.Transaction
import id.walt.gateway.providers.metaco.restapi.transfer.model.AccountTransferParty
import id.walt.gateway.providers.metaco.restapi.transfer.model.AddressTransferParty
import id.walt.gateway.providers.metaco.restapi.transfer.model.Transfer
import id.walt.gateway.providers.metaco.restapi.transfer.model.TransferParty
import id.walt.gateway.usecases.AccountUseCase
import id.walt.gateway.usecases.BalanceUseCase
import id.walt.gateway.usecases.TickerUseCase
import java.time.Instant

class AccountUseCaseImpl(
    private val accountRepository: AccountRepository,
    private val transactionRepository: TransactionRepository,
    private val transferRepository: TransferRepository,
    private val addressRepository: AddressRepository,
    private val balanceUseCase: BalanceUseCase,
    private val tickerUseCase: TickerUseCase,
) : AccountUseCase {
    override fun profile(domainId: String, parameter: ProfileParameter): Result<List<ProfileData>> = runCatching {
        accountRepository.findAll(domainId, emptyMap()).items.map {
            ProfileData(accountId = it.data.id, alias = it.data.alias, addresses = emptyList(), tickers = emptyList())
        }
    }

    override fun balance(parameter: AccountParameter): Result<AccountBalance> = runCatching {
//        profile(parameter).fold(onSuccess = {
//            balanceUseCase.list(parameter).getOrElse { emptyList() }
//        }, onFailure = { throw it }).let {
//            AccountBalance(it)
//        }
        TODO()
    }

    override fun balance(parameter: BalanceParameter): Result<BalanceData> = runCatching {
        balanceUseCase.get(parameter).getOrThrow()
    }

    override fun transactions(parameter: TradeListParameter): Result<List<TransactionData>> = runCatching {
        transferRepository.findAll(
            parameter.domainId, mapOf(
                "accountId" to parameter.accountId,
                "tickerId" to (parameter.tickerId ?: ""),
            )
        ).items.groupBy { it.transactionId }.map {
            buildTransactionData(parameter, it.key, it.value)
        }.sortedByDescending { Instant.parse(it.date) }
    }

    override fun transaction(parameter: TransactionParameter): Result<TransactionTransferData> = runCatching {
        transactionRepository.findById(parameter.domainId, parameter.transactionId).let { transaction ->
            val transfers = transferRepository.findAll(
                parameter.domainId,
                mapOf("transactionId" to transaction.id)
            ).items
            val ticker = getTickerData(transfers.first().tickerId)
            val amount = computeAmount(transfers)
            TransactionTransferData(
                status = getTransactionStatus(transaction),
                date = transaction.registeredAt,
                total = AmountWithValue(amount, ticker),
                transfers = transfers.map {
                    TransferData(
                        amount = it.value,
                        type = it.kind,
                        address = getRelatedAccount(parameter.domainId, transaction.orderReference == null, transfers),
                    )
                }
            )
        }
    }

    private fun getTickerData(tickerId: String) = tickerUseCase.get(TickerParameter(tickerId)).getOrThrow()

    private fun computeAmount(transfers: List<Transfer>) =
        transfers.filter { it.kind == "Transfer" }.map { it.value.toIntOrNull() ?: 0 }
            .fold(0) { acc, d -> acc + d }.toString()

    private fun getTransactionStatus(transaction: Transaction) =
        transaction.ledgerTransactionData?.ledgerStatus ?: transaction.processing?.status ?: "Unknown"

    private fun buildTransactionData(parameter: TradeListParameter, transactionId: String, transfers: List<Transfer>) =
        let {
            val transaction = transactionRepository.findById(parameter.domainId, transactionId)
            val ticker = getTickerData(parameter.tickerId ?: transfers.first().tickerId)
            val amount = computeAmount(transfers)
            TransactionData(
                id = transaction.id,
                date = transaction.registeredAt,
                amount = amount,
                ticker = ticker,
                //TODO: get outgoing status from order custom properties
                type = transaction.orderReference?.let { "Outgoing" } ?: "Receive",
                status = getTransactionStatus(transaction),
                price = ValueWithChange(
                    Common.computeAmount(amount, ticker.decimals) * ticker.price.value,
                    Common.computeAmount(amount, ticker.decimals) * ticker.price.change
                ),
                relatedAccount = getRelatedAccount(parameter.domainId, transaction.orderReference == null, transfers),
            )
        }

    private fun getRelatedAccount(domainId: String, iAmSender: Boolean, transfers: List<Transfer>) =
        transfers.filter { it.kind == "Transfer" }.let {
            if (iAmSender) {
                getAddresses(domainId, it.mapNotNull { it.recipient })
            } else
                getAddresses(domainId, it.flatMap { it.senders })
        }.firstOrNull() ?: "Unknown"

    private fun getAddresses(domainId: String, transferParties: List<TransferParty>) = transferParties.flatMap {
        if (it is AddressTransferParty) listOf(it.address)
        else (it as AccountTransferParty).addressDetails?.let { listOf(it.address) } ?: addressRepository.findAll(
            domainId,
            it.accountId
        ).items.map { it.address }
    }
}

