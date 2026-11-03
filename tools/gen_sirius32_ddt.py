#!/usr/bin/env python3
"""Gera Sirius32DdtData.kt a partir de um perfil DDT2000 JSON da Sirius 32.

Uso: python3 -I tools/gen_sirius32_ddt.py <perfil.json> <saida.kt>
Perfil usado: SIRIUS32_EMS3132_Soft_RE0_A30 (versão A300, igual ao dump 'A3' do Scenic).
Formato de linha de item (campos separados por TAB):
  nome  firstByte  bitOffset  bitsCount  signed  step  offset  divideBy  unit  labels
labels = "valor=texto" separados por '¦'. Frames = '21' + id local (0xA0...).
"""
import json, sys

src, out = sys.argv[1], sys.argv[2]
d = json.load(open(src, encoding='utf-8', errors='replace'))
D, R = d['data'], {r['name']: r for r in d['requests']}
SEP = '\t'

def clean(s):
    s = str(s)
    for bad in ('\t', '\n', '¦', '$', '"""'):
        assert bad not in s, (bad, s)
    return s

def item_line(name, it):
    spec = D.get(name, {})
    bytes_count = spec.get('bytescount', 1)
    bits = spec.get('bitscount', 8 * bytes_count if 'bitoffset' not in it else 1)
    if 'bitoffset' not in it and 'bitscount' not in spec:
        bits = 8 * bytes_count
    labels = '¦'.join(f"{k}={clean(v)}" for k, v in sorted(spec.get('lists', {}).items(), key=lambda kv: int(kv[0])))
    f = [clean(name), it['firstbyte'], it.get('bitoffset', 0), bits,
         1 if spec.get('signed') else 0, spec.get('step', 1), spec.get('offset', 0),
         spec.get('divideby', 1), clean(spec.get('unit', '')), labels]
    return SEP.join(str(x) for x in f)

frames = []
for rn, r in R.items():
    sb = r['sentbytes']
    if (rn.startswith('Trame') or rn == 'Identification') and sb.startswith('21') and len(sb) == 4:
        lines = [item_line(n, it) for n, it in r['receivebyte_dataitems'].items()]
        frames.append((int(sb[2:], 16), rn, lines))
frames.sort()

faults = []
for dev in d['devices']:
    conds = SEP.join(f"{clean(k)}={v}" for k, v in dev['devicedata'].items())
    faults.append(clean(dev['name']) + '\x01' + conds)  # nome \x01 cond1\tcond2...

def esc(s): return s.replace('\\', '\\\\')

with open(out, 'w', encoding='utf-8') as f:
    f.write('// GERADO por ecu-core/tools/gen_sirius32_ddt.py - NÃO editar à mão.\n')
    f.write(f"// Fonte: perfil DDT2000 '{d['ecuname']}' (autoident {json.dumps(d['autoidents'])}).\n")
    f.write('// Referência a validar em carro: NÃO é captura real da central.\n')
    f.write('package io.ecucore.transport.sirius32\n\ninternal object Sirius32DdtData {\n')
    f.write(f"    const val ECU_NAME = \"{clean(d['ecuname'])}\"\n\n")
    for fid, rn, lines in frames:
        f.write(f"    private const val F{fid:02X} = \"\"\"\n" + '\n'.join(lines) + '\n"""\n')
    f.write('\n    val FRAME_TITLES: Map<Int, String> = mapOf(\n')
    for fid, rn, _ in frames: f.write(f'        0x{fid:02X} to "{clean(rn)}",\n')
    f.write('    )\n\n    val FRAME_ITEMS_TEXT: Map<Int, String> = mapOf(\n')
    for fid, _, _ in frames: f.write(f'        0x{fid:02X} to F{fid:02X},\n')
    f.write('    )\n\n    /** Cada linha: nome \\u0001 condição1 \\t condição2 ... (condição = item=valor). */\n')
    f.write('    const val FAULTS_TEXT = "' + '\\n'.join(esc(x).replace('\x01', '\\u0001').replace('\t', '\\t').replace('"', '\\"') for x in faults) + '"\n}\n')
print(len(frames), 'frames', len(faults), 'faults')
