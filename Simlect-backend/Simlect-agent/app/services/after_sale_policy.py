"""Small, versioned lexical policy retrieval; independent of disabled embeddings."""
import json
import re
from pathlib import Path

POLICY_FILE=Path(__file__).resolve().parents[2]/'knowledge/after_sale_policy.json'

def query_policy(question: str) -> str:
    if not question or len(question)>1000:
        return '请提供1000字以内的售后政策问题。'
    try:
        corpus=json.loads(POLICY_FILE.read_text(encoding='utf-8'))
        if (not isinstance(corpus,dict) or not isinstance(corpus.get('version'),str)
                or not isinstance(corpus.get('scope'),str) or not isinstance(corpus.get('documents'),list)
                or not corpus['documents']):
            raise ValueError('Invalid policy corpus')
        for doc in corpus['documents']:
            if (not isinstance(doc,dict) or not all(isinstance(doc.get(k),str) for k in ('id','title','content'))
                    or not isinstance(doc.get('keywords'),list)
                    or not all(isinstance(k,str) and k for k in doc['keywords'])):
                raise ValueError('Invalid policy document')
        question=re.sub(r'([7七])\s+天',r'\1天',question)
        hits=[d for d in corpus['documents'] if any(k in question for k in d['keywords'])]
        if not hits:
            return '现有售后政策未收录此问题，请联系人工客服；不作推测。'
        # Literal excerpts, not model-generated promises. Include safety limits for all queries.
        limits=next((d for d in corpus['documents'] if d['id']=='AS-004'),None)
        if limits is None:
            raise ValueError('Missing limitations document')
        if limits not in hits:
            hits.append(limits)
        return f"政策来源：knowledge/after_sale_policy.json；版本：{corpus['version']}；{corpus['scope']}。\n"+'\n'.join(f"[{d['id']}] {d['title']}：{d['content']}" for d in hits)
    except (OSError,ValueError,KeyError):
        return '售后政策暂不可用，请联系人工客服；不作推测。'
