package de.fu_berlin.inf.dpp.activities.business;

import de.fu_berlin.inf.dpp.User;
import de.fu_berlin.inf.dpp.activities.SPath;
import de.fu_berlin.inf.dpp.activities.serializable.IActivityDataObject;
import de.fu_berlin.inf.dpp.activities.serializable.JupiterActivityDataObject;
import de.fu_berlin.inf.dpp.concurrent.jupiter.Operation;
import de.fu_berlin.inf.dpp.concurrent.jupiter.Timestamp;
import de.fu_berlin.inf.dpp.project.ISarosSession;

/**
 * A JupiterActivity is an Activity that can be handled by the Jupiter
 * Algorithm.
 */
public class JupiterActivity extends AbstractActivity {

    /**
     * Timestamp that specifies the definition context of the enclosed
     * operation.
     */
    private final Timestamp timestamp;

    private final Operation operation;

    private final SPath editor;

    public JupiterActivity(Timestamp timestamp, Operation operation,
        User source, SPath editor) {

        super(source);
        this.timestamp = timestamp;
        this.operation = operation;
        this.editor = editor;
    }

    public Operation getOperation() {
        return this.operation;
    }

    public Timestamp getTimestamp() {
        return this.timestamp;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (!super.equals(obj))
            return false;
        if (getClass() != obj.getClass())
            return false;
        JupiterActivity other = (JupiterActivity) obj;
        if (editor == null) {
            if (other.editor != null)
                return false;
        } else if (!editor.equals(other.editor))
            return false;
        if (operation == null) {
            if (other.operation != null)
                return false;
        } else if (!operation.equals(other.operation))
            return false;
        if (timestamp == null) {
            if (other.timestamp != null)
                return false;
        } else if (!timestamp.equals(other.timestamp))
            return false;
        return true;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = super.hashCode();
        result = prime * result + ((editor == null) ? 0 : editor.hashCode());
        result = prime * result
            + ((operation == null) ? 0 : operation.hashCode());
        result = prime * result
            + ((timestamp == null) ? 0 : timestamp.hashCode());
        return result;
    }

    @Override
    public String toString() {
        StringBuilder buffer = new StringBuilder();
        buffer.append("JupiterActivity(");
        buffer.append(this.timestamp);
        buffer.append(",");
        buffer.append(this.operation);
        buffer.append(",");
        buffer.append(this.getSource());
        buffer.append(")");
        return buffer.toString();
    }

    public SPath getEditorPath() {
        return this.editor;
    }

    public void dispatch(IActivityReceiver receiver) {
        receiver.receive(this);
    }

    public IActivityDataObject getActivityDataObject(ISarosSession sarosSession) {
        return new JupiterActivityDataObject(timestamp, operation,
            source.getJID(), editor.toSPathDataObject(sarosSession));
    }
}
