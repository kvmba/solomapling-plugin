package soloMapling.companion.memory;

import java.util.List;

/** Produces semantic text from an already stable-ordered episodic group. */
@FunctionalInterface
public interface MemorySummarizer {

    String summarize(List<MemoryRecord> episodes);
}
