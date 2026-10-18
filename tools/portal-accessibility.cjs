/* Checks computed colors and names in the real browser, including alpha backgrounds. */

function inspectAccessibility() {
  const failures = [];
  const samples = [];
  const dialog = document.querySelector('dialog[open]');
  const scope = dialog || document.body;
  const rgba = value => {
    const values = value.match(/[\d.]+/g)?.map(Number) || [];
    return [values[0] || 0, values[1] || 0, values[2] || 0, values[3] ?? 1];
  };
  const blend = (fg, bg) => fg.slice(0,3).map((value,i) => value*fg[3]+bg[i]*(1-fg[3]));
  const luminance = color => color.slice(0,3).map(value => {
    const channel=value/255; return channel<=.04045 ? channel/12.92 : ((channel+.055)/1.055)**2.4;
  }).reduce((total,value,i) => total+value*[.2126,.7152,.0722][i],0);
  const contrast = (a,b) => (Math.max(luminance(a),luminance(b))+.05)/(Math.min(luminance(a),luminance(b))+.05);
  function background(element) {
    const parents=[];
    for(let current=element;current;current=current.parentElement) parents.unshift(current);
    return parents.reduce((color,parent) => blend(rgba(getComputedStyle(parent).backgroundColor),color),[255,255,255]);
  }
  const visible = element => element.getClientRects().length && getComputedStyle(element).visibility !== 'hidden'
    && element.getBoundingClientRect().bottom > 0;
  function checkColor(element, style, label) {
    const bg=background(element), fg=blend(rgba(style.color),bg);
    const large=parseFloat(style.fontSize)>=24 || (parseFloat(style.fontSize)>=18.66 && Number(style.fontWeight)>=700);
    const ratio=contrast(fg,bg), required=large?3:4.5;
    samples.push({ label, ratio:Math.round(ratio*100)/100, required });
    if(ratio < required) failures.push(`${label}: contrast ${ratio.toFixed(2)} < ${required}`);
  }
  const walker=document.createTreeWalker(scope,NodeFilter.SHOW_TEXT);
  while(walker.nextNode()) {
    const text=walker.currentNode, element=text.parentElement;
    if(!text.textContent.trim() || !visible(element) || element.closest('script,style,svg,[aria-hidden=true],:disabled')) continue;
    checkColor(element,getComputedStyle(element),element.id || String(element.className) || element.tagName.toLowerCase());
  }
  for(const input of scope.querySelectorAll('input,select,textarea')) {
    if(!visible(input) || input.type==='hidden' || input.disabled) continue;
    if(!input.labels?.length && !input.getAttribute('aria-label') && !input.getAttribute('aria-labelledby'))
      failures.push(`Unnamed field: ${input.name || input.id}`);
    checkColor(input,getComputedStyle(input),`field ${input.name || input.id}`);
    if(input.placeholder) checkColor(input,getComputedStyle(input,'::placeholder'),`placeholder ${input.name}`);
  }
  for(const button of scope.querySelectorAll('button')) {
    if(!visible(button) || button.disabled) continue;
    if(!button.textContent.trim() && !button.getAttribute('aria-label')) failures.push('Unnamed button');
    const rect=button.getBoundingClientRect();
    if(rect.width<24 || rect.height<24) failures.push(`Small target: ${button.id || button.textContent}`);
  }
  for(const modal of scope.matches('dialog') ? [scope] : scope.querySelectorAll('dialog')) {
    if(!modal.getAttribute('aria-label') && !document.getElementById(modal.getAttribute('aria-labelledby')))
      failures.push(`Unnamed dialog: ${modal.id}`);
  }
  return { failures, samples, controls:scope.querySelectorAll('button,input,select').length };
}

async function audit(evaluate, stage) {
  const result=await evaluate(`(${inspectAccessibility.toString()})()`);
  return { stage, ...result };
}
module.exports={ audit };
