package ixoroturg;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit test for simple App.
 */
public class AppTest {

    /**
     * Rigorous Test :-)
     */

	IPool<StringBuilder> pool = null;
    @Test
    public void shouldAnswerWithTrue() {
		pool = IPool.<StringBuilder>newBuilder()
			.size(16)
			.scale(2)
			.generator(()->{
				StringBuilder builder = new StringBuilder(1024);
				return builder;
			})
			.reset((builder)->{
				builder.setLength(0);
				return builder;
			})
			.autoReset()
			.build();
		
		var entry = pool.open();
		entry.value.append("Hello");
		assert("Hello".equals(entry.value.toString()));
		entry.close();
		entry = pool.open();
		entry.value.append("world");
		assert("world".equals(entry.value.toString()));
		assert(pool.getOpenAttempts() == 2);
		assert(pool.getAvailableObjects() == 1);
		assert(pool.getPoolSize() == 16);

    }
}
