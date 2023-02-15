package id.walt.gateway.providers.metaco.restapi

import id.walt.gateway.dto.coins.CoinData
import id.walt.gateway.dto.exchanges.ExchangeData
import id.walt.gateway.dto.exchanges.ExchangeParameter
import id.walt.gateway.providers.metaco.CoinMapper
import id.walt.gateway.providers.metaco.repositories.TickerRepository
import id.walt.gateway.usecases.CoinUseCase
import id.walt.gateway.usecases.ExchangeUseCase
import java.net.URLDecoder

class ExchangeUseCaseImpl(
    private val tickerRepository: TickerRepository,
    private val coinUseCase: CoinUseCase,
) : ExchangeUseCase {
    private val uuidRegex = Regex("[a-zA-Z0-9]{8}(-[a-zA-Z0-9]{4}){3}-[a-zA-Z0-9]{12}")
    override fun exchange(parameter: ExchangeParameter): Result<ExchangeData> = runCatching {
        val fromName = getTokenName(URLDecoder.decode(parameter.from, "utf-8"))
        val toName = getTokenName(URLDecoder.decode(parameter.to, "utf-8"))
        val fromPrice = coinUseCase.metadata(CoinMapper.mapNameToCoinParameter(fromName, "eur")).getOrThrow().let {
            getPrice(it, parameter.type)
        }
        val toPrice = coinUseCase.metadata(CoinMapper.mapNameToCoinParameter(toName, "eur")).getOrThrow().let {
            getPrice(it, parameter.type)
        }
        parameter.amount.toDoubleOrNull()?.let { amount ->
            if (toPrice == .0) throw IllegalArgumentException("Division by zero.")
            if (fromPrice == .0) throw IllegalArgumentException("Division by zero.")
            ExchangeData(
                amount = (amount * fromPrice / toPrice).toString(),
                unitPrice = (toPrice / fromPrice).toString()
            )
        } ?: throw IllegalArgumentException("Couldn't parse input data")
    }

    private fun getPrice(coin: CoinData, type: String) = when (type.lowercase()) {
//        "sale" -> coin.bidPrice ?: coin.askPrice
//        "purchase" -> coin.askPrice
        else -> coin.askPrice
    }

    private fun getTokenName(input: String): String = if (uuidRegex.matches(input)) {
        runCatching { tickerRepository.findById(input).data.name }.getOrDefault(input)
    } else {
        input
    }
}