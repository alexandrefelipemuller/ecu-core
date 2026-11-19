# Chrysler/Jeep OBD1 (SBEC, 1991-95) via SCI

Referência extraída de xj-scanner (MZULALI), ChryslerScanner (László Dániel) e AR1972/jeep_2.5L.
Código: `ChryslerSciTransport`, `chrysler/ChryslerSciProtocol`, `chrysler/ChryslerSciDtcTable`.
Validado em UM carro: Jeep Cherokee XJ 1994 4.0L, PCM 56030117. Não validado por este projeto.

## Licenças (decidir antes de distribuir)
ecu-core é MIT; as fontes são GPLv3. `ChryslerSciDtcTable.kt` é cópia dos textos de DTC do
ChryslerScanner (não só fatos). IDs/escalas/sequência foram reescritos em Kotlin.
Não copiamos os sketches Arduino nem os scripts Python.

## Camada física
- SCI: 7812,5 baud, 8N1, lógica invertida (repouso LOW com chave ligada), 5 V. ELM327 não fala isso.
- Conector de 6 cavidades sob o capô, numeradas por coluna (1-2 esq., 3-4 meio, 5-6 dir.). XJ 94:
  2 = GND, 3 = PCM RX (~5 V, a PCM escuta), 4 = PCM TX (~0 V, a PCM fala), 6 = **+12 V chaveado, nunca ligar**
  (mesma cor preto/branco do GND!), 1 e 5 vazias. Medir antes: chave ON, um ~0 V com continuidade (GND),
  um ~5 V, um ~0 V sem continuidade; se um "0 V" pula a 12 V por 1-2 s é a bomba, não é o TX.
- Ponte: Arduino Nano com SoftwareSerial invertido (D10 = RX do Arduino <- PCM TX; D11 = TX -> PCM RX),
  pull-up interno em D10 (pull-up externo de 1 kΩ impediu a recepção). USB 115200:
  `XJBRIDGE 1.0` ao resetar; `PROBE` -> `IDLE HIGH% n` (conectado, chave ON: ~0); `GO I`/`GO N` -> modo
  transparente. O transporte espera essa interface; qualquer ponte (ESP32...) que a imite serve.
- Cada byte enviado é ecoado: enviar um por vez, esperando o eco (rajadas são perdidas).
- Algumas PCMs trocam os nibbles de cada byte: detectado pela tensão da bateria (PID 0A) entre 9 e 16,5 V.

## Comandos
| Envia | Efeito |
|---|---|
| `10` | códigos: eco `10`, 1 byte/código, marcadores `FE` (nenhum)/`FD`, checksum (soma, 8 bits). `10 FE 0E` = sem códigos |
| `14 id` | lê parâmetro; PCM repete o valor até receber `00` |
| `15 hi lo` | lê 1 byte de memória (somente leitura). ROM $8000-$FFFF (~18 min); nº de peça em $8002-5 (BCD) |
| `13 id` / `13 00` | liga / para atuador (bobinas 01-03, bicos 04-06/1D-1F, IAC 07, ventoinha 08, A/C 09, ASD 0A, purga 0B, bomba 33...) |
| `17` | apaga códigos **e zera o trim aprendido** |
| `19 rpm/8` | segura marcha lenta (reenviar ~2x/s; só estacionado) |
| `23 02` | zera trim de longo prazo (resposta `F0`) |
| `1A id` | testes de chave/switch (tabela do ChryslerScanner) |

## Parâmetros e armadilhas
Tabela completa: `ChryslerSciProtocol.PARAMS`. Pontos que enganam:
- **PID 0E (long-term trim)**: 128 = neutro; 1 contagem = 1/384 do combustível (~0,26 %); a PCM limita a 108..168
  (-5,2 %..+10,4 %). A fórmula de bit de sinal do ChryslerScanner inverte o sentido (raw 110 = -4,7 %, não +21,6 %).
  Confirmado no código desmontado da PCM (AR1972).
- **PID 41 (velocidade)** lê o dobro no carro de teste: `vehicleSpeedFactor = 0.5` após conferir no velocímetro.
- **PID 3B/57 (status do sistema de combustível)** não respondem: closed loop só se infere do O2 cruzando ~0,45 V.
- PID 10 é o mesmo byte que RPM/32 (não é "fuel command"). PID 08 (TPS mínimo) lê 1,02 V com TPS fechado em 0,63 V (inexplicado).
- `17` e `23 02` zeram o aprendizado: não apagar antes de uma volta de comparação.
- TPS em % no `EcuLiveData` é aproximação (assume 0,5-4,5 V); O2 é narrowband (volts, `o2UpstreamVolts`), `o2` marcado indisponível.

## Outros achados úteis do ChryslerScanner / AR1972
- ChryslerScanner configura o SCI por baud: 976, 7812 (OBD1), 62500, 125000 (SCI de alta velocidade das PCMs/TCMs
  posteriores, 96+) e polaridade invertida=OBD1/normal=OBD2; também fala CCD (barramento 1984-2003) e PCI.
  Documentação: `Documentation/ChryslerCCDSCIScanner_UART_Protocol.pdf` e `ChryslerScanner_V2_API_Reference.md`.
- AR1972/jeep_2.5L: ROMs de PCMs 91-95 (4 e 6 cil.), utilitários SBEC, pinouts XJ/YJ/ZJ, DRB II. Fonte para validar escalas.
- Próximos passos possíveis: `tools/pid_sweep` equivalente (varrer 00-FF e listar quem responde, em outra PCM),
  dump de ROM para identificar a PCM, integração no AutoDetect (hoje o transporte é escolhido manualmente),
  ponte própria (ESP32 BT/Wi-Fi) para o app Android sem cabo USB.
