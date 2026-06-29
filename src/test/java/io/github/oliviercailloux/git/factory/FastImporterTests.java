package io.github.oliviercailloux.git.factory;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class FastImporterTests {
  @Test
  void testBasic() throws Exception {
    String content = Resourcer.charSource("basic.fast-export").read();
    assertTrue(content.length() > 0);
    fail("Not yet implemented");
  }
}
