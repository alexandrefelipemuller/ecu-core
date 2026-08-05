# ecu-core

Núcleo multiplataforma (Kotlin Multiplatform) compartilhado pelos apps
**SpeeduinoManager** (Android / Desktop / iOS) e **TopSpeed** (Android / iOS).
Pacote raiz: `io.ecucore`. Targets: `android`, `jvm("desktop")`, `iosArm64`,
`iosSimulatorArm64`.

## Módulos

```
core-tuning ──► core-model ◄── core-protocol ◄── core-runtime
                    ▲                                  │
                    └──────────────────────────────────┘
```

| Módulo | Conteúdo |
|---|---|
| `:core-model` | Modelos de tabela (VE/Ignition/AFR/Dwell), definitions por ECU (Speeduino/MS2/MS3/Rusefi), parsing .ini, parsers de live data, validação, BaseMapGenerator, helpers `shared/` (Logger, Crc32Table, MonotonicClock, NumberFormat) |
| `:core-protocol` | SpeeduinoProtocol (commonMain, CRC32 puro), conexões (ISpeeduinoConnection; TCP jvm, TCP nativo/BLE ios, serial jSerialComm desktop), cache de páginas |
| `:core-runtime` | SpeeduinoClient (commonMain, implementa EcuTransport), SessionController, sync (SessionTableParser, ConfigSyncService), transports OBD2 domain, LiveLogRecorder |
| `:core-tuning` | TuningAssistantAnalyzer/Models, units/UnitSystem, BeforeAfterLogCompare |

Consumidores dependem por coordenada, resolvida via composite build:
`implementation("io.ecucore:core-runtime")` (+ `core-tuning` quando usado).

## Como os apps consomem

Cada repo de app tem este repositório como **git submodule** em `ecu-core/` e
`includeBuild("ecu-core")` no `settings.gradle.kts`. Requisitos do consumidor:
Gradle 9.6+, Kotlin 2.4.0, e (Android) AGP 9.2+.

## Fluxo de trabalho diário

**Toda mudança de código compartilhado nasce aqui, nunca numa cópia local.**

1. Edite dentro de `<app>/ecu-core/` (o IDE mostra o composite build como projeto editável).
2. `cd ecu-core && ./gradlew allTests` (roda desktop + android host + iOS simulator).
3. Commit + push no ecu-core; depois `git add ecu-core && git commit` no app para fixar o pin.
4. Nos demais apps: `git submodule update --remote ecu-core`, rodar os testes, commitar o pin.

Se um pin ficar para trás, o app continua funcionando com a versão fixada — mas
não deixe divergir por muito tempo: pin desatualizado é um fork disfarçado.

## Testes e qualidade

- `./gradlew allTests` — todos os targets (os commonTest rodam também em Kotlin/Native no simulador iOS).
- `./gradlew jacocoTestReport` — cobertura por módulo (desktopTest).
- `./gradlew sonar -Psonar.token=...` — projeto Sonar próprio (`ecu-core`), separado do Sonar dos apps.
- Golden tests: `Crc32TableDifferentialTest` (CRC puro vs `java.util.zip.CRC32`) e os testes de framing do protocolo protegem a portabilidade binária do port JVM→common.

## Regras de portabilidade (commonMain)

`commonMain` compila para Kotlin/Native — **proibido**: `java.*`, `String.format`,
`System.currentTimeMillis/nanoTime`, `synchronized`, `ConcurrentHashMap`.
Use os substitutos em `io.ecucore.shared`: `formatDecimal`, `toHex02`, `MonotonicClock`,
`Crc32Table`, `sleepMillis`, `@JvmSynchronized`. O compilador dos targets ios
acusa qualquer vazamento no build — não desative esses targets.

## Guia de extensão para o TopSpeed (migração futura)

O TopSpeed (fork rebrand) deve migrar para consumir este repo em vez de manter
cópia própria. Divergências conhecidas e como absorvê-las **sem fork**:

1. **`RestoreTablePageValidator`** — já incorporado em `:core-model` (aditivo).
2. **Valet mode (`EngineProtectionConfig`)** — o TopSpeed adiciona
   `isValetMode()/asValetMode()/hardRevLimit*` e usa um mapeamento de páginas
   diferente (páginas 6/4/1/9 vs página única). Caminho: estender
   `EngineProtectionConfig` com os campos extras (defaults neutros) e extrair o
   mapeamento de páginas do `EngineProtectionMapper` para um `EcuPageLayout`
   injetável com default = comportamento atual. Mudança aditiva no ecu-core; o
   TopSpeed injeta seu layout.
3. **Factory reset OEM (`SpeeduinoProtocol`/`SpeeduinoClient`, diff ~1100 linhas)** —
   não reconciliar por merge textual. Introduzir interface `ProtocolExtensions`
   (hooks para comandos extras) em `:core-protocol` e mover os comandos OEM do
   TopSpeed para uma implementação dessa interface no app TopSpeed.
4. **Pacote** — o shared do TopSpeed Android usa `com.topspeed.managerEFI`; na
   migração os imports passam a `io.ecucore` (mesmo processo mecânico usado nos
   apps SpeeduinoManager: reescrever imports por lista de símbolos exportados).
5. **TopSpeedAppiOS** — já usa `com.speeduino.manager` e é quase idêntico ao
   SpeeduinoManageriOS; a migração é o mesmo passo-a-passo do iOS principal
   (o `OemFactoryReset` fica no app, fora do core).

## Histórico

Criado em 2026-08 unificando 5 cópias divergentes do módulo `shared`
(SpeeduinoManagerAndroid — fonte da verdade do core —, Desktop, iOS e os dois
TopSpeed). Tag `pre-unification` em cada repo marca o estado anterior.

## Licença

MIT — ver [LICENSE](LICENSE).
