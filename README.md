# rutex 
Kotlin cryptocurrency exchange interface
* supports Bitfinex, Kraken, ~~WEX~~
* supports Depth, Trade fee, Withdraw fee monitoring
* implemets Balances, Orders(create\monitor\cancel), Withdraw methods

##Uses
* [HkariCP](https://github.com/brettwooldridge/HikariCP) as SQL connection pool
* [Exposed](https://github.com/JetBrains/Exposed) as SQL DSL
* [Ktor](https://github.com/ktorio/ktor) as web framework
* Kotlin coroutines, actors, channels to handle high frequency depth update

##Build and run
use
```gradle
gradlew build
```
then
```gradle
gradlew run
```
open localhost:9009 to watch implemented stocks info
