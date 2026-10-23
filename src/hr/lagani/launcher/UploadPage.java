package hr.lagani.launcher;

/** The page a laptop/phone browser gets at the box's address. */
final class UploadPage {
    private UploadPage() {}

    static final String HTML = "<!doctype html><html lang=hr><head><meta charset=utf-8>"
            + "<meta name=viewport content='width=device-width,initial-scale=1'>"
            + "<title>Pošalji na TV box</title><style>"
            + "body{font-family:system-ui,sans-serif;background:#0b0f14;color:#fff;margin:0;padding:24px;max-width:640px;margin:auto}"
            + "h1{font-size:24px}label.drop{display:block;border:2px dashed #42a5f5;border-radius:12px;padding:40px 16px;"
            + "text-align:center;font-size:18px;cursor:pointer}label.drop.over{background:#14304d}"
            + "input{display:none}.f{margin:12px 0;font-size:15px}.bar{height:8px;background:#222;border-radius:4px;overflow:hidden}"
            + ".bar i{display:block;height:100%;width:0;background:#42a5f5}.ok{color:#66bb6a}.err{color:#ef5350}"
            + "</style></head><body><h1>Pošalji na TV box</h1>"
            + "<label class=drop id=drop>Dodirni ovdje ili povuci datoteke<input type=file id=pick multiple></label>"
            + "<div id=list></div><script>"
            + "var pick=document.getElementById('pick'),drop=document.getElementById('drop'),list=document.getElementById('list'),q=[],busy=false;"
            + "pick.onchange=function(){add(pick.files);pick.value=''};"
            + "drop.ondragover=function(e){e.preventDefault();drop.className='drop over'};"
            + "drop.ondragleave=function(){drop.className='drop'};"
            + "drop.ondrop=function(e){e.preventDefault();drop.className='drop';add(e.dataTransfer.files)};"
            + "function add(fs){for(var i=0;i<fs.length;i++){var d=document.createElement('div');d.className='f';"
            + "d.innerHTML='<div></div><div class=bar><i></i></div>';d.firstChild.textContent=fs[i].name;list.appendChild(d);"
            + "q.push({file:fs[i],el:d})}next()}"
            + "function next(){if(busy||!q.length)return;busy=true;var it=q.shift(),x=new XMLHttpRequest(),bar=it.el.querySelector('i');"
            + "x.open('PUT','/upload?name='+encodeURIComponent(it.file.name));"
            + "x.upload.onprogress=function(e){if(e.lengthComputable)bar.style.width=(100*e.loaded/e.total)+'%'};"
            + "x.onload=function(){var ok=x.status==200;it.el.firstChild.className=ok?'ok':'err';"
            + "it.el.firstChild.textContent=(ok?'\\u2713 ':'\\u2717 ')+it.file.name;bar.style.width='100%';busy=false;next()};"
            + "x.onerror=function(){it.el.firstChild.className='err';it.el.firstChild.textContent='\\u2717 '+it.file.name+' (provjeri je li ekran Primi datoteke otvoren na TV-u)';busy=false;next()};"
            + "x.send(it.file)}"
            + "</script></body></html>";
}
