package im.conversations.android.xmpp.model.reply;

import androidx.annotation.NonNull;

import com.google.common.base.Strings;

import eu.siacs.conversations.xml.Namespace;
import im.conversations.android.annotation.XmlElement;
import im.conversations.android.xmpp.model.Extension;

@XmlElement(namespace = Namespace.XMPP_REPLY)
public class Reply extends Extension {

    public Reply() {
        super(Reply.class);
    }

    public Reply(final String id, final String to) {
        this();
        this.setId(id);
        this.setTo(to);
    }

    public String getId() {
        return Strings.emptyToNull(this.getAttribute("id"));
    }

    public void setId(@NonNull final String id) {
        this.setAttribute("id", id);
    }

    public String getTo() {
        return Strings.emptyToNull(this.getAttribute("to"));
    }

    public void setTo(@NonNull final String to) {
        this.setAttribute("to", to);
    }
}
