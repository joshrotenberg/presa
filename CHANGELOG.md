# Changelog

## [0.1.8](https://github.com/joshrotenberg/presa/compare/v0.1.7...v0.1.8) (2026-11-13)


### Features

* serve Agent Skills with the Skills extension (SEP-2640) ([#155](https://github.com/joshrotenberg/presa/issues/155)) ([084ef5f](https://github.com/joshrotenberg/presa/commit/084ef5f04ca9013d82957bbdbb3797f3e1d4f048))

## [0.1.7](https://github.com/joshrotenberg/presa/compare/v0.1.6...v0.1.7) (2026-11-13)


### Features

* log internal errors with their stack trace to the transport's log ([#153](https://github.com/joshrotenberg/presa/issues/153)) ([3b5c3cf](https://github.com/joshrotenberg/presa/commit/3b5c3cf5205b41d900155c8743fe80ec29f9d318))

## [0.1.6](https://github.com/joshrotenberg/presa/compare/v0.1.5...v0.1.6) (2026-11-12)


### Bug Fixes

* proxy-server returns once it follows each backend ([#151](https://github.com/joshrotenberg/presa/issues/151)) ([6b2c2fa](https://github.com/joshrotenberg/presa/commit/6b2c2fa9835fc8eeed3f52cc2f7fb7c28ce00d15))

## [0.1.5](https://github.com/joshrotenberg/presa/compare/v0.1.4...v0.1.5) (2026-11-12)


### Features

* a loopback-bound HTTP server accepts only loopback Host headers by default ([#146](https://github.com/joshrotenberg/presa/issues/146)) ([80f6a47](https://github.com/joshrotenberg/presa/commit/80f6a47c5141a176a3943cbc26f0d9461419cdb9))


### Bug Fixes

* close the remaining auth gaps from the survey ([#145](https://github.com/joshrotenberg/presa/issues/145)) ([8a4d716](https://github.com/joshrotenberg/presa/commit/8a4d716e9e8c40d9f3637054d971c20abdf37676))
* internal errors no longer send exception messages to the client ([#142](https://github.com/joshrotenberg/presa/issues/142)) ([d39b6da](https://github.com/joshrotenberg/presa/commit/d39b6da22084bac4efc9cd73b0edd24d0863494a))
* narrow concurrency and shutdown races in the server and connection ([#149](https://github.com/joshrotenberg/presa/issues/149)) ([0811817](https://github.com/joshrotenberg/presa/commit/0811817d040588a5f2bf4492defc5f4bf4d28cff))
* SSE resumption, close and parsing leftovers in the HTTP transport ([#150](https://github.com/joshrotenberg/presa/issues/150)) ([c830a79](https://github.com/joshrotenberg/presa/commit/c830a795cab2d108659e43de624e1cd7429a9160))
* the stdio client transport ends its server and the server's children ([#143](https://github.com/joshrotenberg/presa/issues/143)) ([87a9490](https://github.com/joshrotenberg/presa/commit/87a949015af515a21a07bd32fd781626ffa3a7f6))

## [0.1.4](https://github.com/joshrotenberg/presa/compare/v0.1.3...v0.1.4) (2026-11-12)


### Features

* :allow-http? lets the OAuth client use plain-http authorization servers ([#128](https://github.com/joshrotenberg/presa/issues/128)) ([a45d1c0](https://github.com/joshrotenberg/presa/commit/a45d1c00e64a17af2efb188a61865dbd7d104e6a))
* bound finished tasks with :max-finished, and reap on reads ([#125](https://github.com/joshrotenberg/presa/issues/125)) ([aa43d24](https://github.com/joshrotenberg/presa/commit/aa43d241a0e7da04cbc936df3cf8ac2af2afd09c))
* cap initialize-era HTTP sessions per authenticated subject ([#127](https://github.com/joshrotenberg/presa/issues/127)) ([dd5a4b2](https://github.com/joshrotenberg/presa/commit/dd5a4b20f6e27df0e6c8ad463a257a734caa7f26))
* settable per-request progress and log caps ([#130](https://github.com/joshrotenberg/presa/issues/130)) ([42ca8aa](https://github.com/joshrotenberg/presa/commit/42ca8aa44158aaec4121d04b3b3f7bd8bd71ad25))
* the OAuth client renews an expired token with its refresh token ([#129](https://github.com/joshrotenberg/presa/issues/129)) ([2d2af54](https://github.com/joshrotenberg/presa/commit/2d2af5413b3224221f6f5ec365c9d79e6e2338d8))


### Bug Fixes

* requestState and input answers have one shape on every path ([#124](https://github.com/joshrotenberg/presa/issues/124)) ([46dfd91](https://github.com/joshrotenberg/presa/commit/46dfd91800115ecdf229c9f426a0441f83c050bf))

## [0.1.3](https://github.com/joshrotenberg/presa/compare/v0.1.2...v0.1.3) (2026-11-12)


### Features

* cancel stdio requests in flight after :drain-timeout-ms once stdin closes ([#113](https://github.com/joshrotenberg/presa/issues/113)) ([95e10ec](https://github.com/joshrotenberg/presa/commit/95e10eca9162a587a4ebca779fb856ef2fc13491))


### Bug Fixes

* a malformed percent-encoded resource URI is not found, not an internal error ([#117](https://github.com/joshrotenberg/presa/issues/117)) ([f909c5b](https://github.com/joshrotenberg/presa/commit/f909c5b75c41c363561c774eb136a43c0796d4d8))
* a second initialize drops the replaced session's listeners ([#120](https://github.com/joshrotenberg/presa/issues/120)) ([fb35f05](https://github.com/joshrotenberg/presa/commit/fb35f054f518f90c83e97bdd7bb118bdc6728ae2))
* advertise list changes for kinds a :list-changed server starts without ([#107](https://github.com/joshrotenberg/presa/issues/107)) ([599e9f0](https://github.com/joshrotenberg/presa/commit/599e9f029bf6dba5db37ff3ea691f52a96ce89a7))
* answer a request whose handler throws an Error or whose result cannot be written ([#106](https://github.com/joshrotenberg/presa/issues/106)) ([6d1d900](https://github.com/joshrotenberg/presa/commit/6d1d9005a3f6708a9ca0cae8b5ac352704059650))
* destroy a bb task's processes when its tool call is cancelled ([#109](https://github.com/joshrotenberg/presa/issues/109)) ([d312cee](https://github.com/joshrotenberg/presa/commit/d312ceef1704b9bb16be8b9c4c66bd5c62b9adc1))
* evict idle initialize-era HTTP sessions and cap their number ([#78](https://github.com/joshrotenberg/presa/issues/78)) ([52b5c7b](https://github.com/joshrotenberg/presa/commit/52b5c7b62c4c2018bad10b1dd2f16b2705d86b19))
* JWKS fetches once at a time, drops removed keys, and typ can be required ([#111](https://github.com/joshrotenberg/presa/issues/111)) ([8abcf14](https://github.com/joshrotenberg/presa/commit/8abcf14c5df3c65af40a371e3452654894c4edff))
* let a cancel abort an HTTP client request waiting on a JSON response ([#115](https://github.com/joshrotenberg/presa/issues/115)) ([3dfe70d](https://github.com/joshrotenberg/presa/commit/3dfe70dbeff4bedf870a27f9b83b0517736a0f0d))
* part/proxy lists by capability, forwards progress, takes timeouts ([#110](https://github.com/joshrotenberg/presa/issues/110)) ([2441d63](https://github.com/joshrotenberg/presa/commit/2441d63346f0b741b7e7d3025da4fd1437f8e984))
* part/serve! closes its parts when the process is signalled ([#112](https://github.com/joshrotenberg/presa/issues/112)) ([e6abfb1](https://github.com/joshrotenberg/presa/commit/e6abfb1b79b7fc35f7b66c0b1e0f6adf0ec84028))
* pass bb task tool args when the server keywordizes arguments ([#74](https://github.com/joshrotenberg/presa/issues/74)) ([3ad6053](https://github.com/joshrotenberg/presa/commit/3ad60535753d5aef0374eb6d75ea360bcefbe891))
* proxy resyncs run one at a time per backend and keep names unique ([#122](https://github.com/joshrotenberg/presa/issues/122)) ([75f8a92](https://github.com/joshrotenberg/presa/commit/75f8a92f72d87d15194ef09ba7abad7ae433a91a))
* register an HTTP request as running before its work starts ([#80](https://github.com/joshrotenberg/presa/issues/80)) ([77e414f](https://github.com/joshrotenberg/presa/commit/77e414fdeda5627c21b22158faa3401b8ee755b1))
* release a task's thread when its TTL passes ([#114](https://github.com/joshrotenberg/presa/issues/114)) ([6581711](https://github.com/joshrotenberg/presa/commit/6581711227e27606eaa1d92cb361e2ffeeb01533))
* renew an initialize-era HTTP session the server no longer knows ([#79](https://github.com/joshrotenberg/presa/issues/79)) ([4e4e26d](https://github.com/joshrotenberg/presa/commit/4e4e26d5a20b91bc18f71689e710af16bdfc58d1))
* stop leaking a thread per HTTP request ([#77](https://github.com/joshrotenberg/presa/issues/77)) ([22e23df](https://github.com/joshrotenberg/presa/commit/22e23dfdcaac0fe2946779a92a8cef15061c0f38))
* the HTTP client transport forgets a request's answered id when it is done ([#119](https://github.com/joshrotenberg/presa/issues/119)) ([acd2cff](https://github.com/joshrotenberg/presa/commit/acd2cff17471dc0aeba531132a814eda9002245a))
* the OAuth client requires advertised PKCE support and HTTPS endpoints ([#116](https://github.com/joshrotenberg/presa/issues/116)) ([81e18a8](https://github.com/joshrotenberg/presa/commit/81e18a833b91ebc406928decff33e7413e03621e))
* validate and send tool results with keyword keys as their JSON form ([#108](https://github.com/joshrotenberg/presa/issues/108)) ([7031a2a](https://github.com/joshrotenberg/presa/commit/7031a2a3082ca6705f6a94f434f13564fffa6162))

## [0.1.2](https://github.com/joshrotenberg/presa/compare/v0.1.1...v0.1.2) (2026-11-06)


### Features

* a Clojars MCP server example ([#65](https://github.com/joshrotenberg/presa/issues/65)) ([73df4bf](https://github.com/joshrotenberg/presa/commit/73df4bf519b8684404546da98fa4ea828b3505a9))
* keyword schemas and keyword tool arguments ([#68](https://github.com/joshrotenberg/presa/issues/68)) ([c41347d](https://github.com/joshrotenberg/presa/commit/c41347dc3db2419c2f5d02f3b4d71ce514391f95))
* presa.part and presa.bb, servers composed from parts ([#72](https://github.com/joshrotenberg/presa/issues/72)) ([ab05c87](https://github.com/joshrotenberg/presa/commit/ab05c87bfe3f2831f815e4ef21eb43afcf43cbe0))

## [0.1.1](https://github.com/joshrotenberg/presa/compare/v0.1.0...v0.1.1) (2026-11-04)


### Bug Fixes

* let the lint allow GitHub's co-author trailer for bot pull requests ([#62](https://github.com/joshrotenberg/presa/issues/62)) ([13d0612](https://github.com/joshrotenberg/presa/commit/13d06124d18ac9298900eeeef2a30d24f3e6adbe))

## 0.1.0 (2026-11-04)


### Features

* a complete notebook example with a REPL walkthrough (closes [#49](https://github.com/joshrotenberg/presa/issues/49)) ([#52](https://github.com/joshrotenberg/presa/issues/52)) ([25a55a5](https://github.com/joshrotenberg/presa/commit/25a55a584d045cf3f7073547fb9b66835e76980c))
* cancel 2026-07-28 HTTP requests from a posted cancel within the caller's subject ([a211b75](https://github.com/joshrotenberg/presa/commit/a211b751dfc1e8de572b61b51c6f984a4f6c2e24))
* cancel stateless HTTP requests from a posted notifications/cancelled (closes [#16](https://github.com/joshrotenberg/presa/issues/16)) ([04105b4](https://github.com/joshrotenberg/presa/commit/04105b4c359b8cb73ae66aaa7ba339aa36697d1a))
* change a running server's tools, resources and prompts ([fcfec4b](https://github.com/joshrotenberg/presa/commit/fcfec4b23ec7f1829c77ec8b9be6bd3005c3330c))
* change a running server's tools, resources and prompts (closes [#9](https://github.com/joshrotenberg/presa/issues/9)) ([4c9f7ac](https://github.com/joshrotenberg/presa/commit/4c9f7acba31a4b1435cb3eeb952257a526b7c504))
* CI for tests, conventions and conformance lanes ([9c02083](https://github.com/joshrotenberg/presa/commit/9c020832ffd0c4a0988cc81d2d6f03e538b62648))
* client caching from ttlMs and cacheScope ([53c7539](https://github.com/joshrotenberg/presa/commit/53c7539612587bdaca09cec44eaf461f4d14bc39))
* client caching from ttlMs and cacheScope (closes [#12](https://github.com/joshrotenberg/presa/issues/12)) ([e8c329f](https://github.com/joshrotenberg/presa/commit/e8c329f9c8a5c6dbf07410bbf583d0255341bc0f))
* client conformance lanes ([058d285](https://github.com/joshrotenberg/presa/commit/058d285bd7a163fa26de6f269f0d585194cb2716))
* client conformance leg and its CI lane (closes [#5](https://github.com/joshrotenberg/presa/issues/5)) ([f9ee10b](https://github.com/joshrotenberg/presa/commit/f9ee10b808fc281515776723e60d48c167e299a3))
* client with in-process and stdio transports ([0e3ef80](https://github.com/joshrotenberg/presa/commit/0e3ef80b5127c2892084dd0728830d7eb1520592))
* conformance fixture and runner script ([bb4eaef](https://github.com/joshrotenberg/presa/commit/bb4eaefdc23544954c8f8e0a131942b8af36339d))
* DPoP for the OAuth client (closes [#42](https://github.com/joshrotenberg/presa/issues/42)) ([#54](https://github.com/joshrotenberg/presa/issues/54)) ([f0c428b](https://github.com/joshrotenberg/presa/commit/f0c428ba9379670ca2263a7c76f39e93e66737d3))
* forward backend logs and initialize-era input through the proxy (closes [#45](https://github.com/joshrotenberg/presa/issues/45)) ([#51](https://github.com/joshrotenberg/presa/issues/51)) ([f5b1b04](https://github.com/joshrotenberg/presa/commit/f5b1b04feb8b6f577edd281e90fee077044a3e26))
* forward cancellation through the proxy (closes [#59](https://github.com/joshrotenberg/presa/issues/59)) ([#60](https://github.com/joshrotenberg/presa/issues/60)) ([2e86ec3](https://github.com/joshrotenberg/presa/commit/2e86ec36938679c7929495198b8c7ab43ac235cb))
* full JSON Schema 2020-12 validation as an optional dependency ([4dbe7bd](https://github.com/joshrotenberg/presa/commit/4dbe7bd7fd802fa0ce8470dd4f92f3380f7e8548))
* full JSON Schema 2020-12 validation as an optional dependency (closes [#13](https://github.com/joshrotenberg/presa/issues/13)) ([cdb458c](https://github.com/joshrotenberg/presa/commit/cdb458cc5045bbb13517f067fa4e6d406ae9908b))
* GET event stream for initialize-era HTTP sessions ([2bf4279](https://github.com/joshrotenberg/presa/commit/2bf427963b9de3adc7a030a007aeb00af70cdc1b))
* ignore notifications with malformed _meta, and cancellations with malformed params ([96db6fe](https://github.com/joshrotenberg/presa/commit/96db6fe3b07a2b4c65a6242631deaa474cdd7e7c))
* initialize-era notifications and conformance lanes for them ([1f65c30](https://github.com/joshrotenberg/presa/commit/1f65c306470e4ae4501ec202a388a56b041a4b2f))
* instrumentation events from presa.server ([826bd2d](https://github.com/joshrotenberg/presa/commit/826bd2d1f127ac6e72bd79ee266c240adb4d03d9))
* instrumentation hooks (closes [#17](https://github.com/joshrotenberg/presa/issues/17)) ([2748f08](https://github.com/joshrotenberg/presa/commit/2748f0863de1311bbffed3bc9c31742602c985da))
* JWT-bearer and enterprise-managed grants in the OAuth client (closes [#43](https://github.com/joshrotenberg/presa/issues/43)) ([#55](https://github.com/joshrotenberg/presa/issues/55)) ([627233d](https://github.com/joshrotenberg/presa/commit/627233d6a5568a00cafc830fafdcbc91934763d4))
* limits on message size, open streams and notifications ([f36c25f](https://github.com/joshrotenberg/presa/commit/f36c25f104bd867286c5febfd86e206dcb9756e1))
* limits on message size, open streams and notifications (closes [#10](https://github.com/joshrotenberg/presa/issues/10)) ([8bad0fd](https://github.com/joshrotenberg/presa/commit/8bad0fd6a461df245fb1c25cc63bd54f0c42a952))
* log messages from handlers with per-request and per-session levels ([57fbd23](https://github.com/joshrotenberg/presa/commit/57fbd2350939e6ee85903b8041dd26c5956c73c9))
* multi round-trip requests with signed request state ([c7be236](https://github.com/joshrotenberg/presa/commit/c7be2361930258c4192046efa726829b3e0995fb))
* OAuth 2.1 client flows for presa.client ([2a6b9ee](https://github.com/joshrotenberg/presa/commit/2a6b9ee0b49f457f3293231b2dec6a37c6cdf6fe))
* OAuth 2.1 client flows for presa.client (closes [#8](https://github.com/joshrotenberg/presa/issues/8)) ([d759f9b](https://github.com/joshrotenberg/presa/commit/d759f9b529bfeae6473df0128c7e3a3c5f63be38))
* OAuth 2.1 resource server for the HTTP transport ([63d8dbe](https://github.com/joshrotenberg/presa/commit/63d8dbeecc85493298cc9ce972a02abb28d51017))
* OAuth 2.1 resource server for the HTTP transport (closes [#7](https://github.com/joshrotenberg/presa/issues/7)) ([8776f40](https://github.com/joshrotenberg/presa/commit/8776f40b82c8380b6a651d8eee28b1aef4b82c74))
* progress notifications and cancellation over stdio ([717addc](https://github.com/joshrotenberg/presa/commit/717addce67a4db329444367ceadee5fdcdacc1fe))
* project skeleton with protocol revisions and JSON-RPC framing ([052f263](https://github.com/joshrotenberg/presa/commit/052f263d0fd745797e81fcb1a7845946a3116ae2))
* proxy that aggregates several MCP servers ([b03eed0](https://github.com/joshrotenberg/presa/commit/b03eed01c16eb46808aced03b4e6ef51ca8c7647))
* proxy that aggregates several MCP servers (closes [#18](https://github.com/joshrotenberg/presa/issues/18)) ([90081b0](https://github.com/joshrotenberg/presa/commit/90081b01b0fe3997195ec00bac19922ca1c078f0))
* requests from initialize-era servers to clients ([cdec364](https://github.com/joshrotenberg/presa/commit/cdec36469fe039c9bb851c613bf225be1247cfb1))
* resources, prompts, completion, pagination and caching hints ([f4d1b03](https://github.com/joshrotenberg/presa/commit/f4d1b03917ad9d4d041be5770e7d056b2e7ca2de))
* standalone event stream for initialize-era HTTP sessions (closes [#11](https://github.com/joshrotenberg/presa/issues/11)) ([181fd7c](https://github.com/joshrotenberg/presa/commit/181fd7ca62d956b10e011c4c1ca0ae9369231bdd))
* stdio tools server with an initialize opt-in ([412d93f](https://github.com/joshrotenberg/presa/commit/412d93f7a052db0abc1d261fc7962cb0a4b8c379))
* stdio tools server with an initialize opt-in ([152f75c](https://github.com/joshrotenberg/presa/commit/152f75c060b58490cf6abff65a310f73fbee4828))
* Streamable HTTP server and client transport ([3ef4221](https://github.com/joshrotenberg/presa/commit/3ef4221fd397208e0623048b2f7bd6cad542f83a))
* subscriptions/listen ([8e25e83](https://github.com/joshrotenberg/presa/commit/8e25e837b9afaf9d4ebccd711d48e84515083a83))
* task status notifications on subscriptions/listen ([#48](https://github.com/joshrotenberg/presa/issues/48)) ([60afc14](https://github.com/joshrotenberg/presa/commit/60afc14d2612db5f948f53807059b5b7112f778c))
* Tasks extension ([6044ac5](https://github.com/joshrotenberg/presa/commit/6044ac54412a48523b1d986328855de5611af0a7))
* Tasks extension (closes [#6](https://github.com/joshrotenberg/presa/issues/6)) ([0c18c7c](https://github.com/joshrotenberg/presa/commit/0c18c7c384e5829c5665671866968c310181f1dc))
* validate _meta on notifications (closes [#15](https://github.com/joshrotenberg/presa/issues/15)) ([670de30](https://github.com/joshrotenberg/presa/commit/670de30ee4f00190db41236bafcc5afc46470ee5))
* validate tool arguments and structured output against their schemas ([4a2c9e2](https://github.com/joshrotenberg/presa/commit/4a2c9e2586012430675da71d1ecdda50137e3c53))


### Bug Fixes

* apply :required-scopes to completion and resource subscriptions ([#58](https://github.com/joshrotenberg/presa/issues/58)) ([4a9f145](https://github.com/joshrotenberg/presa/commit/4a9f14528e7b14456c345cb987e6d48f86603d93))
* call a listen callback before delivering the acknowledgement ([98aba8e](https://github.com/joshrotenberg/presa/commit/98aba8e8c1caa817fb22e29d6b6d959feb651f15))
* follow the 2026-07-28 wire rules for meta, ping and errors ([e3c4eca](https://github.com/joshrotenberg/presa/commit/e3c4ecae82107aa8510f41ac916ffc6eae74b646))
* hide tools whose schemas are not objects from initialize-era clients (closes [#14](https://github.com/joshrotenberg/presa/issues/14)) ([2204dc2](https://github.com/joshrotenberg/presa/commit/2204dc2f24feec456f8f7f8e841a5f154b637fde))
* hide tools without object schemas from initialize-era clients ([359df87](https://github.com/joshrotenberg/presa/commit/359df87baa6415993dca903b981a54b4b2e32b08))
* lint the pull request's own commits, not GitHub's merge commit ([46fcae0](https://github.com/joshrotenberg/presa/commit/46fcae0ac8d81c98c521d5c5c664edad5183a45a))
* omit empty params on initialize-era list notifications, and test them with an initialize-era server ([378b021](https://github.com/joshrotenberg/presa/commit/378b021775894e218bf6d3c7aebcc86a873dc1a3))
* open the session stream before a request sent after initialized ([#53](https://github.com/joshrotenberg/presa/issues/53)) ([96ed12d](https://github.com/joshrotenberg/presa/commit/96ed12d81115befd49207be41f06fa526d0c6200))
* pre-release pass (JWT exp, client version), docs, tests and badges ([#56](https://github.com/joshrotenberg/presa/issues/56)) ([623bc57](https://github.com/joshrotenberg/presa/commit/623bc57be47364af573f12211cb5f890e98c70aa))
* register a subscription listener before acknowledging it ([63d1f61](https://github.com/joshrotenberg/presa/commit/63d1f610aadb7fc1a7883e6fc6db7393a617767c))
* skip merge commits in bb lint ([c62c203](https://github.com/joshrotenberg/presa/commit/c62c203a7290113982bbbaa25ff8fa6135c73844))
