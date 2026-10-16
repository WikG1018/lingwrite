# SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
# SPDX-License-Identifier: AGPL-3.0-only
"""由 extracted.json（简体中文源）和 tr-<lang>.json 生成 values*/strings.xml 与 I18nMap.kt。
用法：python3 tools/i18n/gen.py（在仓库根目录或任意目录均可）。改界面文案请改这里的 JSON 再重新生成。"""
import json, sys, pathlib
HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent.parent
RES = ROOT / 'android/app/src/main/res'
SRC = ROOT / 'android/app/src/main/java/com/lingwrite/app/i18n'
d = json.load(open(HERE / 'extracted.json'))['entries']

def esc(t):
    t = t.replace('\\', '\\\\').replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace("'", "\\'").replace('"', '\\"').replace('\n', '\\n').replace('\t', '\\t')
    if t[:1] in '@?': t = '\\' + t
    return t

EN = json.load(open(HERE / 'tr-en.json'))

def write(folder, texts, header):
    out = ['<?xml version="1.0" encoding="utf-8"?>', '<!-- SPDX-License-Identifier: AGPL-3.0-only -->', '<!-- %s -->' % header, '<resources>']
    for k, (t, n, f) in d.items():
        v = (texts.get(k) if texts is not None else t)
        if v is None: continue
        attr = '' if n or '%' not in v else ' formatted="false"'
        # 提示词片段、正则、中文数字等只在默认资源里，不需要翻译
        if texts is None and k not in EN: attr += ' translatable="false"'
        ev = esc(v)
        if v != v.strip(' '): ev = '"%s"' % ev  # 首尾空格要加引号，否则 aapt 会吃掉
        out.append('    <string name="%s"%s>%s</string>' % (k, attr, ev))
    out.append('</resources>')
    (RES / folder).mkdir(exist_ok=True)
    (RES / folder / 'strings.xml').write_text('\n'.join(out) + '\n')

if __name__ == '__main__':
    write('values', None, '简体中文（默认）。由 ui/ 源码抽取；data_* 为数据层标签（显示时经 trText 翻译）。')
    for lang, folder in [('en', 'values-en'), ('zh-TW', 'values-zh-rTW'), ('ja', 'values-ja')]:
        p = HERE / ('tr-%s.json' % lang)
        if p.exists():
            texts = json.load(open(p)); write(folder, texts, lang)
    # zh source text -> id lookup for data-layer labels / ids
    items = [(t, k) for k, (t, n, f) in d.items() if n == 0]
    chunks = [items[i:i+250] for i in range(0, len(items), 250)]
    kt = ['// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors', '// SPDX-License-Identifier: AGPL-3.0-only', '// 自动生成（tools/i18n/gen.py）：中文原文 → 字符串资源，供数据层标签和内部 id 显示时翻译。', 'package com.lingwrite.app.i18n', '', 'import com.lingwrite.app.R', '']
    for i, ch in enumerate(chunks):
        kt.append('private fun part%d(m: HashMap<String, Int>) {' % i)
        for t, k in ch:
            kt.append('    m[%s] = R.string.%s' % (json.dumps(t, ensure_ascii=False).replace('$', '\\$'), k))
        kt.append('}')
    kt.append('private val zhToRes: HashMap<String, Int> by lazy { HashMap<String, Int>(%d).also { m -> %s } }' % (len(items) * 2, '; '.join('part%d(m)' % i for i in range(len(chunks)))))
    kt.append('')
    kt.append('/** 把中文原文（数据层标签、内部 id）翻译成当前界面语言；查不到就原样返回。 */')
    kt.append('fun trText(zh: String): String = zhToRes[zh]?.let { runCatching { tr(it) }.getOrNull() } ?: zh')
    (SRC / 'I18nMap.kt').write_text('\n'.join(kt) + '\n')
    print('ok', len(d), len(items))
