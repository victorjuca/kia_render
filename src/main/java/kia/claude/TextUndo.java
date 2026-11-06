package kia.claude;

import javax.swing.event.DocumentEvent;
import javax.swing.event.UndoableEditEvent;
import javax.swing.text.AbstractDocument;
import javax.swing.text.BadLocationException;
import javax.swing.undo.CompoundEdit;
import javax.swing.undo.UndoableEdit;
import javax.swing.undo.UndoManager;

/** Historial de deshacer de un documento de letra: agrupa lo que se teclea seguido (como un editor de texto normal). */
final class TextUndo implements javax.swing.event.UndoableEditListener {
    final javax.swing.undo.UndoManager um = new javax.swing.undo.UndoManager(); private javax.swing.undo.CompoundEdit cur; private int lastType = -1, lastOff = -1, lastEnd = -1; private long lastT;
    boolean bulk = false, mute = false;
    TextUndo() { um.setLimit(2000); }
    void endCur() { if (cur != null) { cur.end(); cur = null; } lastType = -1; }
    void clear() { endCur(); um.discardAllEdits(); }
    public void undoableEditHappened(javax.swing.event.UndoableEditEvent e) {
        if (mute) return; javax.swing.undo.UndoableEdit ue = e.getEdit();
        if (!(ue instanceof AbstractDocument.DefaultDocumentEvent de)) return;
        if (de.getType() == DocumentEvent.EventType.CHANGE) return;
        int type = de.getType() == DocumentEvent.EventType.INSERT ? 0 : 1, off = de.getOffset(), len = de.getLength(); long now = System.currentTimeMillis(); boolean nl = false;
        if (type == 0) { try { nl = de.getDocument().getText(off, len).contains("\n"); } catch (BadLocationException ignored) { } }
        boolean contiguous = type == lastType && (type == 0 ? off == lastEnd : (off == lastOff || off + len == lastOff));
        boolean join = bulk ? cur != null : (cur != null && len == 1 && !nl && contiguous && now - lastT < 1200);
        if (join) cur.addEdit(ue); else { endCur(); cur = new javax.swing.undo.CompoundEdit(); cur.addEdit(ue); um.addEdit(cur); }
        lastType = type; lastOff = off; lastEnd = off + len; lastT = now;
        if (!bulk && (nl || len != 1)) endCur();
    }
    boolean undo() { endCur(); if (!um.canUndo()) return false; um.undo(); lastType = -1; return true; }
    boolean redo() { endCur(); if (!um.canRedo()) return false; um.redo(); lastType = -1; return true; }
}
