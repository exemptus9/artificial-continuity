/* Fictional portability vectors. No user recordings or transcripts. */
const fs=require('node:fs'),path=require('node:path');
const golden=()=>JSON.parse(fs.readFileSync(path.join(__dirname,'fixtures/reviewed-audio-golden.json'),'utf8'));
function reseal(p,A){for(const e of p.excerpts){e.id=A.excerptId(e);e.revision_sha256=A.revisionDigest(e);}p.selection.count=p.excerpts.length;p.bundle_sha256=A.bundleDigest(p.excerpts);return p;}
function two(A){const p=golden(),e=structuredClone(p.excerpts[0]);e.segment.sequence=2;e.text.machine='Unselected fictional cactus observation.';e.text.reviewed='Unselected fictional cactus observation.';e.text.effective=e.text.reviewed;e.review.note='Second fictional selection.';e.review.history=[{revised_at:p.exported_at,old:{edited_text:null,verified:false,pinned:false,note:''},new:{edited_text:e.text.reviewed,verified:true,pinned:true,note:e.review.note}}];p.excerpts.push(e);return reseal(p,A);}
module.exports={golden,reseal,two};
