package coinffeine.model.exchange

import coinffeine.model.bitcoin._
import coinffeine.model.currency.Currency.Bitcoin
import coinffeine.model.currency.{CurrencyAmount, BitcoinAmount, FiatCurrency}
import coinffeine.model.market.Price
import coinffeine.model.network.PeerId
import coinffeine.model.payment.PaymentProcessor

case class Exchange[C <: FiatCurrency, +S <: Exchange.State[C]](
    id: ExchangeId,
    role: Role,
    counterpartId: PeerId,
    amounts: Exchange.Amounts[C],
    parameters: Exchange.Parameters,
    blockedFunds: Exchange.BlockedFunds,
    state: S) {

  val currency: C = amounts.netFiatExchanged.currency

  val progress: Exchange.Progress[C] = state.progress
  require(progress.bitcoinsTransferred <= amounts.netBitcoinExchanged,
    "invalid running exchange instantiation: " +
      s"progress $progress is inconsistent with amounts $amounts")
}

object Exchange {

  /** Configurable parameters of an exchange.
    *
    * @param lockTime  The block number which will cause the refunds transactions to be valid
    * @param network   Bitcoin network
    */
  case class Parameters(lockTime: Long, network: Network)

  case class PeerInfo(paymentProcessorAccount: PaymentProcessor.AccountId, bitcoinKey: KeyPair)

  /** How the exchange is break down into steps */
  case class StepBreakdown(intermediateSteps: Int) {
    require(intermediateSteps > 0, s"Intermediate steps must be positive ($intermediateSteps given)")
    val totalSteps = intermediateSteps + 1
  }

  trait StepAmounts[C <: FiatCurrency] {
    val depositSplit: Both[BitcoinAmount]
    val progress: Progress[C]
  }

  /** Amounts involved on one exchange step.
    *
    * @param depositSplit   How to distribute funds on this step
    * @param fiatAmount     Net fiat amount to change hands on this step
    * @param fiatFee        Fiat fees to be payed on this step
    */
  case class IntermediateStepAmounts[C <: FiatCurrency](
      override val depositSplit: Both[BitcoinAmount],
      fiatAmount: CurrencyAmount[C],
      fiatFee: CurrencyAmount[C],
      override val progress: Progress[C]) extends StepAmounts[C] {
    require(!depositSplit.forall(_.isNegative),
      s"deposit split amounts must be non-negative ($depositSplit given)")
    require(fiatAmount.isPositive, s"fiat amount must be positive ($fiatAmount given)")
  }

  case class FinalStepAmounts[C <: FiatCurrency](
      override val depositSplit: Both[BitcoinAmount],
      override val progress: Progress[C]) extends StepAmounts[C]

  /** Characterizes the amounts to be deposited by a part. The difference between input and
    * output is the fee.
    */
  case class DepositAmounts(input: BitcoinAmount, output: BitcoinAmount) {
    require(input.isPositive, "Cannot spent a negative or zero amount")
    require(output.isPositive, "Should not deposit non-positive amount")
    require(input >= output, "Deposits should have a greater or equal input versus output")

    def fee: BitcoinAmount = input - output
  }

  /** Amounts of money involved on an exchange.
    *
    * @param grossBitcoinExchanged  Overall amount of bitcoins to be exchanged (counterpart will
    *                               receive less due to fees)
    * @param grossFiatExchanged     Overall amount of fiat to be exchanged (counterpart will
    *                               receive less due to fees)
    * @param deposits               Exact amounts of bitcoins used on the deposit transactions,
    *                               considering fees
    * @param refunds                Amount refundable by each part after a lock time
    * @param intermediateSteps      Per-step exchanged amounts
    * @param finalStep              Final step amounts
    * @tparam C                     Fiat currency exchanged
    */
  case class Amounts[C <: FiatCurrency](grossBitcoinExchanged: BitcoinAmount,
                                        grossFiatExchanged: CurrencyAmount[C],
                                        deposits: Both[DepositAmounts],
                                        refunds: Both[BitcoinAmount],
                                        intermediateSteps: Seq[IntermediateStepAmounts[C]],
                                        finalStep: FinalStepAmounts[C]) {
    require(grossBitcoinExchanged.isPositive,
      s"Cannot exchange a gross amount of $grossBitcoinExchanged")
    require(grossFiatExchanged.isPositive, s"Cannot exchange a gross amount of $grossFiatExchanged")
    require(intermediateSteps.nonEmpty, "There should be at least one step")

    val currency: C = grossFiatExchanged.currency

    /** Net amount of bitcoins to be exchanged */
    val netBitcoinExchanged: BitcoinAmount =
      finalStep.depositSplit.buyer - deposits.buyer.input
    require(netBitcoinExchanged.isPositive, s"Cannot exchange a net amount of $netBitcoinExchanged")

    /** Net amount of fiat to be exchanged */
    val netFiatExchanged: CurrencyAmount[C] =
      intermediateSteps.foldLeft(CurrencyAmount.zero(currency))(_ + _.fiatAmount)
    require(netFiatExchanged <= grossFiatExchanged)
    require(netFiatExchanged.isPositive, s"Cannot exchange a net amount of $netFiatExchanged")

    val steps: Seq[StepAmounts[C]] = intermediateSteps :+ finalStep

    val price: Price[C] = Price.whenExchanging(netBitcoinExchanged, netFiatExchanged)

    val bitcoinRequired = deposits.map(_.input)
    val fiatRequired = Both(buyer = grossFiatExchanged, seller = CurrencyAmount.zero(currency))

    val breakdown = Exchange.StepBreakdown(intermediateSteps.length)
  }

  /** Funds reserved for the order this exchange belongs to */
  case class BlockedFunds(fiat: Option[PaymentProcessor.BlockedFundsId], bitcoin: BlockedCoinsId)

  type Deposits = Both[ImmutableTransaction]

  case class Progress[C <: FiatCurrency](bitcoinsTransferred: BitcoinAmount,
                                         fiatTransferred: CurrencyAmount[C]) {

    def +(other: Progress[C]) = Progress(
      bitcoinsTransferred = bitcoinsTransferred + other.bitcoinsTransferred,
      fiatTransferred = fiatTransferred + other.fiatTransferred
    )

    override def toString = s"progressed $bitcoinsTransferred by $fiatTransferred"
  }

  def noProgress[C <: FiatCurrency](c: C) = Exchange.Progress(Bitcoin.Zero, CurrencyAmount.zero(c))

  def notStarted[C <: FiatCurrency](id: ExchangeId,
                                    role: Role,
                                    counterpartId: PeerId,
                                    amounts: Exchange.Amounts[C],
                                    parameters: Exchange.Parameters,
                                    blockedFunds: Exchange.BlockedFunds) = Exchange(
    id, role, counterpartId, amounts, parameters, blockedFunds, NotStarted()(amounts.currency))

  sealed trait State[C <: FiatCurrency] {
    val progress: Exchange.Progress[C]
  }

  case class NotStarted[C <: FiatCurrency]()(val currency: C) extends State[C] {
    override val progress = Exchange.noProgress(currency)
  }

  implicit class NonStartedTransitions[C <: FiatCurrency](val exchange: Exchange[C, NotStarted[C]])
    extends AnyVal {

    def startHandshaking(user: Exchange.PeerInfo,
                         counterpart: Exchange.PeerInfo): Exchange[C, Handshaking[C]] =
      exchange.copy(state = Handshaking(user, counterpart)(exchange.currency))
  }

  case class Handshaking[C <: FiatCurrency](user: Exchange.PeerInfo, counterpart: Exchange.PeerInfo)
                                           (val currency: C) extends State[C] with StartedHandshake[C] {
    override val progress = Exchange.noProgress(currency)
  }

  implicit class HandshakingTransitions[C <: FiatCurrency](val exchange: Exchange[C, Handshaking[C]])
    extends AnyVal {

    def startExchanging(deposits: Exchange.Deposits): Exchange[C, Exchanging[C]] =
      exchange.copy(state = Exchanging(exchange.currency, exchange.state, deposits))
  }

  case class Exchanging[C <: FiatCurrency](
      user: Exchange.PeerInfo,
      counterpart: Exchange.PeerInfo,
      deposits: Exchange.Deposits,
      progress: Exchange.Progress[C])
    extends State[C] with StartedExchange[C]

  object Exchanging {
    def apply[C <: FiatCurrency](currency: C,
                                 previousState: Handshaking[C],
                                 deposits: Exchange.Deposits): Exchanging[C] =
      Exchanging(previousState.user, previousState.counterpart, deposits,
        Exchange.noProgress[C](currency))
  }

  implicit class ExchangingTransitions[C <: FiatCurrency](val exchange: Exchange[C, Exchanging[C]])
    extends AnyVal {

    def complete: Exchange[C, Completed[C]] =
      exchange.copy(state = Completed(exchange.amounts, exchange.state))

    def increaseProgress(btcAmount: BitcoinAmount,
                         fiatAmount: CurrencyAmount[C]): Exchange[C, Exchanging[C]] = {
      val progress = exchange.state.progress + Exchange.Progress(btcAmount, fiatAmount)
      exchange.copy(state = exchange.state.copy(progress = progress))
    }
  }

  case class Completed[C <: FiatCurrency](user: Exchange.PeerInfo,
                                          counterpart: Exchange.PeerInfo,
                                          deposits: Exchange.Deposits)(amounts: Exchange.Amounts[C])
    extends State[C] with StartedExchange[C] {
    override val progress = Progress(amounts.netBitcoinExchanged, amounts.netFiatExchanged)
  }

  object Completed {
    def apply[C <: FiatCurrency](amounts: Exchange.Amounts[C],
                                 previousState: Exchanging[C]): Completed[C] =
      Completed(previousState.user, previousState.counterpart, previousState.deposits)(amounts)
  }

  trait StartedHandshake[C <: FiatCurrency] extends State[C] {
    val user: Exchange.PeerInfo
    val counterpart: Exchange.PeerInfo

    require(user.bitcoinKey.hasPrivKey)
  }

  implicit class StartedHandshakePimps(val exchange: Exchange[_, StartedHandshake[_]])
    extends AnyVal {

    def participants: Both[Exchange.PeerInfo] = Both.fromSeq(exchange.role match {
      case BuyerRole => Seq(exchange.state.user, exchange.state.counterpart)
      case SellerRole => Seq(exchange.state.counterpart, exchange.state.user)
    })

    def requiredSignatures: Both[PublicKey] = participants.map(_.bitcoinKey)
  }

  trait StartedExchange[C <: FiatCurrency] extends StartedHandshake[C] {
    val deposits: Exchange.Deposits
  }
}
