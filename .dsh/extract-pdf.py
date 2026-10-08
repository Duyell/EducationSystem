"""提取简历 PDF 的文本（用 PyMuPDF），写到 UTF-8 文件便于阅读。

用法：python .dsh/extract-pdf.py <pdf> <out.txt>
"""
import io
import sys

import fitz

src = sys.argv[1]
out = sys.argv[2]

doc = fitz.open(src)
buf = []
for i, page in enumerate(doc, 1):
    buf.append(f'===== PAGE {i} =====')
    buf.append(page.get_text('text'))
io.open(out, 'w', encoding='utf-8').write('\n'.join(buf))
print('pages:', len(doc))
