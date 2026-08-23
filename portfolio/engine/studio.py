#!/usr/bin/env python3
from __future__ import annotations
import argparse, json, os, subprocess, sys, textwrap
from datetime import datetime, timezone
from pathlib import Path
from urllib import request

REQUIRED_FLAGS = [
    "manifest_valid","source_pack_ready","evidence_ready","story_ready",
    "article_generated","linkedin_generated","visual_spec_ready","visual_rendered",
    "technical_validation_passed","publication_validation_passed","branch_validation_passed"
]

def load(path): return json.loads(Path(path).read_text(encoding="utf-8"))
def save(path,data):
    p=Path(path); p.parent.mkdir(parents=True,exist_ok=True)
    p.write_text(json.dumps(data,indent=2,ensure_ascii=False)+"\n",encoding="utf-8")

def git(*args,allow_fail=False):
    p=subprocess.run(["git",*args],text=True,capture_output=True)
    if p.returncode and not allow_fail: raise RuntimeError(p.stderr.strip() or p.stdout.strip())
    return p.stdout.strip()

def collect_source(d,m):
    base=m.get("source",{}).get("base_branch","develop")
    pack={
      "repository":m.get("source",{}).get("repository"),
      "expected_branch":m["article"]["branch"],
      "current_branch":git("branch","--show-current",allow_fail=True),
      "base_branch":base,
      "changed_files":[x for x in git("diff","--name-only",f"{base}...HEAD",allow_fail=True).splitlines() if x],
      "commits":[x for x in git("log","--pretty=%h %s",f"{base}..HEAD",allow_fail=True).splitlines() if x],
      "collected_at":datetime.now(timezone.utc).isoformat()
    }
    save(d/"source-pack.json",pack); return pack

def build_evidence(m):
    return {"claims":[{"claim":c["claim"],"status":c.get("status","UNSUPPORTED"),"evidence":c.get("evidence",[]),"publishable":c.get("status") in ("VERIFIED","DESIGN_INTENT")} for c in m.get("claims",[])]}

def build_story(m):
    return {"opening":m["article"]["problem"],"thesis":m["article"]["thesis"],"task":m["task"],"journey":m["story"]["journey"],"decisions":m["story"]["decisions"],"takeaway":m["article"]["takeaway"]}

def call_content_studio(prompt,m):
    base=os.getenv("CONTENT_STUDIO_BASE_URL","").rstrip("/"); token=os.getenv("CONTENT_STUDIO_TOKEN","")
    payload=m.get("generation",{}).get("content_studio_payload")
    if not base or not token or not payload: return None
    body=dict(payload); body["topic"]=prompt
    req=request.Request(base+"/api/v1/content-ideas/generate",data=json.dumps(body).encode(),headers={"Authorization":f"Bearer {token}","Content-Type":"application/json"},method="POST")
    with request.urlopen(req,timeout=60) as r: idea=json.loads(r.read().decode())
    req=request.Request(base+f"/api/v1/content-ideas/{idea['id']}/variants",headers={"Authorization":f"Bearer {token}"})
    with request.urlopen(req,timeout=60) as r: variants=json.loads(r.read().decode())
    return variants[0].get("body") if variants else None

def article_text(m,s):
    a,t=m["article"],m["task"]
    out=[f"# {a['title']}\n",f"*{a['subtitle']}*\n",f"**Series:** {a['series']} · **Article {a['number']} / {a['series_length']}**\n",
         f"## The engineering problem\n\n{a['problem']}\n",
         f"## The task we will follow\n\n**{t['id']} — {t['title']}**\n\n{t['description']}\n",
         "## The request journey\n\n"+"\n".join(f"{i+1}. **{x['name']}** — {x['why']}" for i,x in enumerate(s['journey']))+"\n",
         "## What the control plane decides\n\n"+"\n\n".join(f"### {x['title']}\n\n{x['body']}" for x in s['decisions'])+"\n"]
    if m.get("implementation_notes"): out.append("## Implementation shape\n\n"+"\n".join(f"- {x}" for x in m["implementation_notes"])+"\n")
    out.append("## What this boundary prevents\n\n"+f"This design is deliberately defensive. For {t['id']}, the system should never convert a missing fact into an implicit assumption just because execution is possible. It should also avoid treating a successful command as proof that the engineering goal was achieved. The control record keeps the requested outcome, the context used, the actions taken, and the evidence produced as separate concerns. That separation matters when a run is interrupted, when another worker resumes the task, when a human reviews the decision, or when the same scenario is replayed later. The objective is not more automation at any cost; it is automation whose authority, scope and completion criteria remain inspectable.\n")
    out += ["## Evidence, not confidence theater\n\n"+m["story"]["evidence_narrative"]+"\n",f"## The point\n\n{a['takeaway']}\n",f"## What comes next\n\n{a['next_article']}\n"]
    return "\n".join(out)

def linkedin_text(m):
    a,t=m["article"],m["task"]; bullets="\n".join(f"• {x['title']}" for x in m["story"]["decisions"])
    return textwrap.dedent(f"""{a['title']}\n\n{a['hook']}\n\nI use one task throughout the article: {t['id']} — {t['title']}.\n\nThe control plane does three things that matter:\n{bullets}\n\nThe key idea: {a['takeaway']}\n\nArticle {a['number']}/{a['series_length']} in the {a['series']} series.\n""")

def visual_spec(m):
    a,t=m["article"],m["task"]
    return {"title":a["title"],"series":a["series"],"article_badge":f"Article {a['number']} / {a['series_length']}","subtitle":a["subtitle"],"problem":{"task":t["id"],"title":t["title"]},"journey":[x["name"] for x in m["story"]["journey"]],"decisions":[{"title":x["title"],"tone":x.get("tone","blue")} for x in m["story"]["decisions"]],"takeaway":a["takeaway"]}

def esc(s): return str(s).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
def txt(x,y,s,size=24,weight=400,fill="#132238"): return f'<text x="{x}" y="{y}" font-family="Inter,Arial,sans-serif" font-size="{size}" font-weight="{weight}" fill="{fill}">{esc(s)}</text>'
def svg(spec):
    o=['<svg xmlns="http://www.w3.org/2000/svg" width="1600" height="1000" viewBox="0 0 1600 1000">','<rect width="1600" height="1000" fill="#F7F9FC"/>','<rect x="28" y="28" width="8" height="130" rx="4" fill="#246BFD"/>',txt(56,70,spec['series'].upper(),34,700,"#163A70"),txt(56,132,spec['title'],62,800,"#0E1B33"),txt(56,174,spec['subtitle'],24,400,"#526278"),'<rect x="1330" y="35" width="220" height="54" rx="14" fill="#FFFFFF" stroke="#246BFD" stroke-width="2"/>',txt(1360,70,spec['article_badge'],24,700,"#163A70")]
    o += ['<rect x="40" y="220" width="420" height="250" rx="18" fill="#FFFFFF" stroke="#B9C7DA"/>','<rect x="40" y="220" width="420" height="48" rx="18" fill="#246BFD"/>',txt(60,253,"1. The Problem",24,700,"white"),txt(66,310,spec['problem']['task'],24,700,"#163A70"),txt(66,350,spec['problem']['title'][:35],22,600),txt(66,410,"Control plane must explain",22,400,"#526278"),txt(66,442,"what happens next — and why.",22,600)]
    o += ['<rect x="485" y="220" width="1075" height="250" rx="18" fill="#FFFFFF" stroke="#B9C7DA"/>','<rect x="485" y="220" width="1075" height="48" rx="18" fill="#246BFD"/>',txt(505,253,"2. Control Decisions",24,700,"white")]
    colors={"green":"#EAF7EF","amber":"#FFF6DF","red":"#FDEDED","blue":"#EAF2FF"}; border={"green":"#4B9B68","amber":"#D19A2A","red":"#C84A4A","blue":"#4A7FD6"}
    for i,c in enumerate(spec['decisions'][:3]):
        x=510+i*345; tone=c['tone']; o += [f'<rect x="{x}" y="292" width="330" height="145" rx="16" fill="{colors[tone]}" stroke="{border[tone]}" stroke-width="2"/>',txt(x+22,330,c['title'][:27],22,700),txt(x+22,372,"Bounded, explicit, auditable.",20,400,"#526278")]
    o += ['<rect x="40" y="495" width="1520" height="220" rx="18" fill="#FFFFFF" stroke="#B9C7DA"/>','<rect x="40" y="495" width="1520" height="48" rx="18" fill="#246BFD"/>',txt(60,528,"3. Request Journey",24,700,"white")]
    for i,s in enumerate(spec['journey'][:9]):
        x=72+i*145; o += [f'<circle cx="{x+36}" cy="590" r="24" fill="#163A70"/>',txt(x+29,598,str(i+1),20,700,"white"),f'<rect x="{x}" y="625" width="105" height="60" rx="10" fill="#F7F9FC" stroke="#C8D3E3"/>',txt(x+10,657,s[:13],16,700)]
        if i<len(spec['journey'][:9])-1: o.append(f'<line x1="{x+107}" y1="655" x2="{x+137}" y2="655" stroke="#7390B8" stroke-width="2"/>')
    o += ['<rect x="40" y="740" width="920" height="210" rx="18" fill="#FFFFFF" stroke="#B9C7DA"/>','<rect x="40" y="740" width="920" height="48" rx="18" fill="#246BFD"/>',txt(60,773,"4. Evidence Gate",24,700,"white"),txt(70,830,"Claim",20,700,"#526278"),txt(520,830,"Evidence",20,700,"#526278"),txt(785,830,"Verdict",20,700,"#526278"),txt(70,882,"Required facts accounted for",20,500),txt(520,882,"source + tests",20,500),txt(785,882,"VERIFIED",20,700,"#287A47"),'<rect x="985" y="740" width="575" height="210" rx="18" fill="#EEF4FF" stroke="#93B5EC"/>',txt(1010,775,"5. One Line to Remember",24,700,"#163A70")]
    y=830
    for line in textwrap.wrap(spec['takeaway'],38)[:4]: o.append(txt(1020,y,line,26,700)); y+=38
    o.append('</svg>'); return "\n".join(o)

def validate(d,m):
    flags={k:False for k in REQUIRED_FLAGS}; errors=[]
    try:
      for k in ("article","task","story","claims"):
        if k not in m: raise ValueError(f"missing manifest key: {k}")
      flags["manifest_valid"]=True
    except Exception as e: errors.append(str(e))
    req={"source_pack_ready":"source-pack.json","evidence_ready":"evidence.json","story_ready":"story.json","article_generated":"article.md","linkedin_generated":"linkedin.md","visual_spec_ready":"visual-spec.json","visual_rendered":"visual.svg"}
    for flag,fn in req.items():
      p=d/fn; flags[flag]=p.exists() and p.stat().st_size>20
      if not flags[flag]: errors.append(f"missing/empty artifact: {fn}")
    unsupported=[c for c in m.get("claims",[]) if c.get("status")=="UNSUPPORTED"]
    flags["technical_validation_passed"]=not unsupported
    if unsupported: errors.append(f"{len(unsupported)} unsupported claim(s)")
    article=(d/"article.md").read_text(encoding="utf-8") if (d/"article.md").exists() else ""; linkedin=(d/"linkedin.md").read_text(encoding="utf-8") if (d/"linkedin.md").exists() else ""
    flags["publication_validation_passed"]=len(article.split())>=500 and len(linkedin.split())>=60
    if not flags["publication_validation_passed"]: errors.append("publication content below minimum size")
    strict=os.getenv("PORTFOLIO_STRICT_BRANCH","false").lower()=="true"; current=git("branch","--show-current",allow_fail=True); expected=m["article"]["branch"]
    flags["branch_validation_passed"]=(not strict) or current==expected
    if not flags["branch_validation_passed"]: errors.append(f"branch mismatch expected={expected} actual={current}")
    result={"article_id":m["article"]["id"],"flags":flags,"done":all(flags.values()),"errors":errors}; save(d/"validation.json",result); return result

def generate(d):
    m=load(d/"manifest.json"); collect_source(d,m); save(d/"evidence.json",build_evidence(m)); s=build_story(m); save(d/"story.json",s)
    ai=None
    if m.get("generation",{}).get("mode")=="content-studio":
      try: ai=call_content_studio("Create a canonical engineering article using only this manifest: "+json.dumps(m),m)
      except Exception as e: print(f"content-studio unavailable; deterministic renderer used: {e}",file=sys.stderr)
    (d/"article.md").write_text((ai.strip()+"\n") if ai else article_text(m,s),encoding="utf-8"); (d/"linkedin.md").write_text(linkedin_text(m),encoding="utf-8")
    spec=visual_spec(m); save(d/"visual-spec.json",spec); (d/"visual.svg").write_text(svg(spec),encoding="utf-8"); return validate(d,m)

def main():
    p=argparse.ArgumentParser(); sp=p.add_subparsers(dest="cmd",required=True)
    for n in ("generate","validate"): q=sp.add_parser(n); q.add_argument("article_dir")
    a=p.parse_args(); d=Path(a.article_dir); m=load(d/"manifest.json"); r=generate(d) if a.cmd=="generate" else validate(d,m); print(json.dumps(r,indent=2)); sys.exit(0 if r["done"] else 2)
if __name__=="__main__": main()
