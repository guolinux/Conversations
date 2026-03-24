package im.conversations.android.xmpp.model.framing;

import im.conversations.android.annotation.XmlElement;
import im.conversations.android.xmpp.model.StreamElement;

@XmlElement(name = "open")
public class Open extends StreamElement {
    public Open() {
        super(Open.class);
    }
}
