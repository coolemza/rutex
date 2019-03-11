# rutex 
Kotlin cryptocurrency exchange interface
* supports Bitfinex, Kraken, ~~WEX~~
* supports Depth, Trade fee, Withdraw fee monitoring
* implemets Balances, Orders(create\monitor\cancel), Withdraw methods

## Uses
* [HkariCP](https://github.com/brettwooldridge/HikariCP) as SQL connection pool
* [Exposed](https://github.com/JetBrains/Exposed) as SQL DSL
* [Ktor](https://github.com/ktorio/ktor) as web framework
* [Kotlin coroutines](https://github.com/Kotlin/kotlinx.coroutines), actors, channels to handle high frequency depth update

## Build and run
use
```gradle
gradlew build
```
then
```gradle
gradlew run
```
open localhost:9009 to watch implemented stocks info, for wallets info use keys.json like:
```
{
  keys: {
    Kraken:[
      {key:WALLET_KEY,secret:WALLET_KEY_SERET,type:WALLET},
      {key:TRADE_KEY,secret:TRADE_KEY_SECRET,type:TRADE},
      {key:HISTORY_KEY,secret:HISTORY_KEY_SECRET,type:HISTORY},
      {key:WITHDRAW_KEY,secret:WITHDRAW_KEY_SECRET,type:WITHDRAW},
      {key:ACTIVE_KEY,secret:ACTIVE_KEY_SECRET,type:ACTIVE}
    ]
  }
}
```

