/* Existing v0.9 example workspace, not current personal project status. */
window.continuityDemo=()=>({
  version:'0.9.0',view:'now',evidenceCount:2,
  threads:[
    {id:'ux',title:'UX / Product Ideas',state:'CONTINUE',objective:'Capture recurring UX failures and derive reusable design principles.',last:'Intent Integrity and Artificial Continuity converged into a broader thesis.',next:'Promote strongest principle into Personal RFC-001.',open:['Define first public RFC','Test capture workflow on three real products'],waiting:null},
    {id:'brand',title:'BRANDONMEANT',state:'CONTINUE',objective:'Complete the seven-act manuscript architecture.',last:'Reprise placement remains unresolved across the seven acts.',next:'Audit reprise placement across all seven acts.',open:['Final reprise count','Page architecture'],waiting:null},
    {id:'archive',title:'RhymeMosaic Archive',state:'BLOCKED',objective:'Preserve the archive without exposing private historical material.',last:'Public/admin privacy boundary defined; imported items still need review.',next:'Review uncertain imported artifacts.',open:['Retention policy'],waiting:'Privacy review'}
  ],
  captures:[
    {id:'cap-1',type:'friction',text:'Button moved underneath my finger as the interface updated.',context:'mobile UI',at:new Date(Date.now()-3600000).toISOString(),status:'UNPROCESSED'},
    {id:'cap-2',type:'idea',text:'Mark a read conversation as Continue Later rather than pretending read means finished.',context:'ChatGPT',at:new Date(Date.now()-7200000).toISOString(),status:'UNPROCESSED'}
  ],
  ideas:[{id:'idea-1',title:'Intent Integrity',principle:'Persistent human intent should outrank transient interface state.',evidence:['cap-1'],status:'DEVELOPING'}],
  prospective:[{id:'pm-1',title:'Revisit Intent Integrity RFC',condition:{type:'evidence_count',threshold:5},reason:'Deferred until enough independent UX examples exist.',status:'ARMED',firedAt:null}],
  rules:[
    {id:'r1',name:'Preservation',level:'HARD',text:'Never permanently delete original creative work without explicit destructive authorization.'},
    {id:'r2',name:'Uncertainty',level:'HARD',text:'Do not convert uncertain information into fact.'},
    {id:'r3',name:'Agency',level:'HARD',text:'Meaningful external actions require explicit authorization.'},
    {id:'r4',name:'Attention',level:'ADVISORY',text:'Interrupt only when information meaningfully affects an active intention.'},
    {id:'r5',name:'Provenance',level:'HARD',text:'Major state changes must retain reason and source history.'}
  ],
  events:[{id:crypto.randomUUID(),type:'KERNEL_INITIALIZED',reason:'v0.9 Live Lab state created.',at:new Date().toISOString()}],
  sync:{diverged:false,res:{uxState:'base',confidence:'base',next:'base'},adopted:null}
});
