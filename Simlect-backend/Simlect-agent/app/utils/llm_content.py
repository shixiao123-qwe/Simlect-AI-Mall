"""Extract visible text from either chat strings or Responses content blocks."""


def llm_text(content: object) -> str:
    if isinstance(content, str):
        return content
    if not isinstance(content, list):
        return ""
    parts = []
    for block in content:
        if isinstance(block, str):
            parts.append(block)
        elif isinstance(block, dict) and block.get("type") in ("text", "output_text"):
            text = block.get("text")
            if isinstance(text, str):
                parts.append(text)
    return "".join(parts)
