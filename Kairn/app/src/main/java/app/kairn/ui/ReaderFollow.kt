package app.kairn.ui

/**
 * Paints the sentence being read inside the chapter's own DOM, using the CSS Custom Highlight API so a
 * sentence spanning several elements still highlights. Offsets are characters into the chapter text.
 */
internal object ReaderFollow {

    fun highlight(start: Int, end: Int): String = """
        (function(s,e){
          try{
            if(!(window.CSS&&CSS.highlights&&window.Highlight))return;
            if(!window.__kairnHl){
              var st=document.createElement('style');
              st.textContent='::highlight(kairn){background-color:rgba(95,203,170,0.30);}';
              document.head.appendChild(st);
              window.__kairnHl=1;
            }
            var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null),acc=0,sn=null,so=0,en=null,eo=0,n;
            while((n=w.nextNode())){
              var L=n.nodeValue.length;
              if(!sn&&acc+L>s){sn=n;so=s-acc;}
              if(sn&&acc+L>=e){en=n;eo=e-acc;break;}
              acc+=L;
            }
            if(!sn)return;
            if(!en){en=sn;eo=sn.nodeValue.length;}
            so=Math.max(0,Math.min(so,sn.nodeValue.length));
            eo=Math.max(0,Math.min(eo,en.nodeValue.length));
            if(so>=eo)return;
            var r=document.createRange();
            r.setStart(sn,so);r.setEnd(en,eo);
            CSS.highlights.set('kairn',new Highlight(r));
          }catch(x){}
        })($start,$end)
    """.trimIndent()

    fun clear(): String = "try{if(window.CSS&&CSS.highlights)CSS.highlights.delete('kairn');}catch(x){}"
}
