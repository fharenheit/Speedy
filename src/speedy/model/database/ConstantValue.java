package speedy.model.database;

import speedy.SpeedyConstants;

public class ConstantValue implements IValue {

    private Object value;

    public ConstantValue(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("Unable to set NULL as constant value");
        }
        if (value.toString().startsWith(SpeedyConstants.LLUN_PREFIX)) {
            throw new IllegalArgumentException("Trying to create a constant value in place of a llun value");
        }
        this.value = value;
    }

    public Object getPrimitiveValue() {
        return this.value;
    }

    public String getType() {
        return SpeedyConstants.CONST;
    }

    @Override
    public int hashCode() {
        return this.toString().hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        final ConstantValue other = (ConstantValue) obj;
        return this.value.toString().equals(other.value.toString());
    }

    @Override
    public String toString() {
        return value.toString();
    }

}
