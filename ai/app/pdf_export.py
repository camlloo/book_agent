import io
import re
from xml.sax.saxutils import escape


def export_pdf(title, content):
    from reportlab.lib import colors
    from reportlab.lib.enums import TA_LEFT
    from reportlab.lib.styles import ParagraphStyle
    from reportlab.lib.pagesizes import A4
    from reportlab.pdfbase import pdfmetrics
    from reportlab.pdfbase.cidfonts import UnicodeCIDFont
    from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, XPreformatted
    pdfmetrics.registerFont(UnicodeCIDFont("STSong-Light"))
    output = io.BytesIO()
    doc = SimpleDocTemplate(output, pagesize=A4, rightMargin=42, leftMargin=42, topMargin=42, bottomMargin=42, title=title, author="Book_Agent")
    normal = ParagraphStyle("body", fontName="STSong-Light", fontSize=11, leading=18, wordWrap="CJK", spaceAfter=6, alignment=TA_LEFT)
    code_style = ParagraphStyle("code", parent=normal, fontSize=9, leading=13, backColor=colors.HexColor("#f4f6f9"), borderPadding=7)
    story, code = [], False
    for line in content.splitlines():
        if re.match(r"^\s*`{3,}", line): code = not code; story.append(Spacer(1, 6)); continue
        if not line.strip(): story.append(Spacer(1, 5)); continue
        level = len(line) - len(line.lstrip("#")) if not code else 0
        text = line.lstrip("#").strip() if level else line
        text = re.sub(r"\[([^\]]+)\]\((?:https?://|/)[^)]+\)", r"\1", text)
        text = re.sub(r"\*\*([^*]+)\*\*", r"\1", text)
        style = ParagraphStyle("heading", parent=normal, fontSize=max(12, 22-level*2), leading=max(19, 29-level*2), spaceBefore=12, spaceAfter=9) if level else code_style if code else normal
        # Bound long code lines so CJK and code remain printable without horizontal clipping.
        story.append(Paragraph(escape(text).replace(" ", "&#160;") if code else escape(text), style))
    doc.build(story)
    return output.getvalue()
