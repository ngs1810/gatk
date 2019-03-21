package org.broadinstitute.hellbender.tools.copynumber.formats.records;

import org.broadinstitute.hellbender.tools.copynumber.GermlineCNVCaller;

/**
 * Represents a value of the denoised copy ratio output by {@link GermlineCNVCaller}
 *
 * @author Andrey Smirnov &lt;asmirnov@broadinstitute.org&gt;
 */
public class LinearNonLocatableCopyRatio {
    private final double denoisedCopyRatio;

    public LinearNonLocatableCopyRatio(final double denoisedCopyRatio) {
        this.denoisedCopyRatio = denoisedCopyRatio;
    }

    public double getDenoisedCopyRatio() {
        return denoisedCopyRatio;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        LinearNonLocatableCopyRatio that = (LinearNonLocatableCopyRatio) o;

        return Double.compare(that.denoisedCopyRatio, denoisedCopyRatio) == 0;

    }

    @Override
    public int hashCode() {
        long temp = Double.doubleToLongBits(denoisedCopyRatio);
        return (int) (temp ^ (temp >>> 32));
    }

    @Override
    public String toString() {
        return "LinearNonLocatableCopyRatio{" +
                "denoisedCopyRatio=" + denoisedCopyRatio +
                '}';
    }
}
