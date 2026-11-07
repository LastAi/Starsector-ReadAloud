"""Splits dialog text into chunks small enough for TTS models, at natural boundaries.

Synthesizing chunk by chunk lets playback start after the first sentence instead of
after the whole text, and keeps every engine under its input limit.
"""

import re

# Sentence ends: . ! ? … (optionally followed by closing quotes/brackets), then space.
_SENTENCE_END = re.compile(r'(?<=[.!?…])["”’\')\]]*\s+')
_CLAUSE_END = re.compile(r'(?<=[,;:—])\s+')


def split_text(text, max_chars=280, first_max=100):
    """Returns a list of chunks. Paragraphs never merge; sentences merge up to max_chars.

    The first chunk is kept shorter (first_max) so speech starts sooner.
    """
    chunks = []
    for para in re.split(r'\n\s*\n', text or ''):
        para = ' '.join(para.split())
        if not para:
            continue
        current = ''
        for sentence in _sentences(para):
            limit = first_max if not chunks else max_chars
            for piece in _fit(sentence, max_chars):
                if current and len(current) + 1 + len(piece) > limit:
                    chunks.append(current)
                    current = piece
                    limit = max_chars
                else:
                    current = (current + ' ' + piece) if current else piece
        if current:
            chunks.append(current)
    return chunks


def _sentences(para):
    parts = []
    start = 0
    for m in _SENTENCE_END.finditer(para):
        parts.append(para[start:m.start() + len(m.group(0).rstrip())].strip())
        start = m.end()
    tail = para[start:].strip()
    if tail:
        parts.append(tail)
    return [p for p in parts if p]


def _fit(sentence, max_chars):
    """Breaks one over-long sentence at clause marks, then at spaces."""
    if len(sentence) <= max_chars:
        return [sentence]
    out = []
    current = ''
    for clause in _CLAUSE_END.split(sentence):
        for word_chunk in _words(clause, max_chars):
            if current and len(current) + 1 + len(word_chunk) > max_chars:
                out.append(current)
                current = word_chunk
            else:
                current = (current + ' ' + word_chunk) if current else word_chunk
    if current:
        out.append(current)
    return out


def _words(clause, max_chars):
    if len(clause) <= max_chars:
        return [clause]
    out, current = [], ''
    for w in clause.split(' '):
        while len(w) > max_chars:  # a "word" longer than a chunk: hard cut
            if current:
                out.append(current)
                current = ''
            out.append(w[:max_chars])
            w = w[max_chars:]
        if current and len(current) + 1 + len(w) > max_chars:
            out.append(current)
            current = w
        else:
            current = (current + ' ' + w) if current else w
    if current:
        out.append(current)
    return out
