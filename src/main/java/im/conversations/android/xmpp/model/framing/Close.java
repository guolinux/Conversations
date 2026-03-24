package im.conversations.android.xmpp.model.framing;

import im.conversations.android.annotation.XmlElement;
import im.conversations.android.xmpp.model.StreamElement;

@XmlElement(name = "close")
public class Close extends StreamElement {
    public Close() {
        super(Close.class);
    }

    public String getSeeOtherUri() {
        return getAttribute("see-other-uri");
    }
}
