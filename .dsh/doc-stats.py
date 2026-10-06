"""统计八股文档的结构规模（避免在 PowerShell 里跟引号/编码较劲）。"""
import io

p = r'docs/Java方向Agent面试八股.md'
t = io.open(p, encoding='utf-8').read()
lines = t.splitlines()
fence = chr(96) * 3

lines_out = [
    f'行数      : {len(lines)}',
    f'字符数    : {len(t)}',
    f'二级标题  : {sum(1 for l in lines if l.startswith("## "))}',
    f'三级标题  : {sum(1 for l in lines if l.startswith("### "))}',
    f'代码块    : {t.count(fence) // 2}',
    f'表格行    : {sum(1 for l in lines if l.startswith("|"))}',
    f'"人话/举例"提示块: {t.count("人话") + t.count("举例") + t.count("具体")}',
]
io.open(r'.dsh/doc-stats.txt', 'w', encoding='utf-8').write('\n'.join(lines_out) + '\n')
