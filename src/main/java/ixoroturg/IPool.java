package ixoroturg;

import java.util.function.Supplier;
import java.util.function.Function;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class IPool<T> implements AutoCloseable{
	public static final byte RESET = 0, RE_CREATE = 1, CUSTOM = 2, EXCEPTION = 3;
	private IPoolEntry[] pool;
	private Supplier<T> generator;
	private Function<T,T> resetFunction;
	private Predicate<T> validator;
	private Function<T,T> reviver;
	private byte action;
	private int timeout;
	private Consumer<T> closeFunction;
	private boolean autoReset;
	private float scaleFactor;
	private int maxSize;
	private int currentSize = 0;
	private byte isClosing = 0;
	private int opened = 0;
	private byte fallback;
	private int maxRetry;
	
	private int totalFailed = 0;
	private int totalOpened = 0;
	private int totalSuccessOpened = 0;
	private long totalWaitingTime = 0;

	private IPool(int initSize, Supplier<T> generator){
		pool = (IPoolEntry[]) new Object[initSize];
		this.generator = generator;
	}
	public IPoolEntry open(){
		return open(isClosing);
	}
	
	private IPoolEntry open(byte access){
		IPoolEntry entry = null;
		synchronized(this){
			if(access == 1){
				throw new RuntimeException("This IPool is closed");
			}
			for(int i = 0; i < pool.length; i++){
				if(!pool[i].open){
					pool[i].open = true;
					entry = pool[i];
					break;
				}
				if(pool[i] == null){
					if(access == 0 && isClosing == 1){
						throw new RuntimeException("This IPool is closed");
					}
					// else if(access == 2){
					// 	if(currentSize > 0)
					// 		return open(access);
					// }
					if(access == 2)
						continue;
					pool[i] = new IPoolEntry(generator.get());
					entry = pool[i];
					currentSize++;
					break;
				}
			}
			if(entry == null){
				if(currentSize - opened > 0){
					entry = open(access);
				} else if(maxSize != 0 && pool.length == maxSize){
					try {
						long time = System.currentTimeMillis();
						if(timeout == 0 || access == 2){
								this.wait();
						} else {
							this.wait(timeout);
							time = System.currentTimeMillis() - time;
							if (time >= timeout){
								if(currentSize ==  opened)
									throw new RuntimeException("IPool timeout");
							}
						}
						time = System.currentTimeMillis();
						totalWaitingTime += time;
					} catch (InterruptedException e) {
						System.err.println("Error in IPool");
						e.printStackTrace();
					}
					entry = open(access);
				} else if(access != 2){
					int newLength = (int)(pool.length * scaleFactor);
					if(newLength == pool.length){
						newLength++;
					}
					if(newLength > maxSize)
						newLength = maxSize;
					IPoolEntry[] newPool = (IPoolEntry[]) new Object[newLength];
					for(int i = 0; i < pool.length; i++){
						newPool[i] = pool[i];
					}
					pool = newPool;
					entry = open(access);
				} else {
					if(entry == null){
						entry = open(access);
					}
				}
			}
			opened++;
			totalOpened++;
		}
		if(access == 2){
			return entry;
		}
		if(autoReset){
			entry.reset();
		}
		entry.invalid = false;
		validate(entry);
		entry.retry = 0;
		totalSuccessOpened++;
		return entry;
	}
	private void validate(IPoolEntry entry){
		if(validator != null && !validator.test(entry.value)){
			byte testAction = action;
			if(entry.invalid){
				entry.close();
				throw new RuntimeException("Cannot validate object");
			}
			if(entry.retry >= maxRetry)
				testAction = fallback;
			entry.retry++;
			totalFailed++;
			switch(testAction){
				case RESET -> {
					if(resetFunction == null){
						throw new RuntimeException("Reset function not given");
					}
					if(!autoReset)
						entry.value = resetFunction.apply(entry.value);
				}
				case RE_CREATE -> {
					if(closeFunction != null)
						closeFunction.accept(entry.value);
					entry.value = generator.get();
				}
				case CUSTOM -> {
					if(reviver == null){
						entry.close();
						throw new RuntimeException("Used CUSTOM as invalid action, but no revive function given");
					}
					T test = reviver.apply(entry.value);
					if(test == null){
						entry.close();
						throw new RuntimeException("Could not revive object");
					}
					entry.value = test;
				}
				case EXCEPTION -> {
					entry.close();
					throw new RuntimeException("Invalid pool object");
				}
			}
			if(entry.retry >= maxRetry)
				entry.invalid = true;
			validate(entry);
		}
	}

	public int getOpenAttempts(){
		return totalOpened;
	}
	public int getSuccessOpenAttempts(){
		return totalSuccessOpened;
	}
	public int getTotalFailed(){
		return totalFailed;
	}
	public long getTotalWaitingTime(){
		return totalWaitingTime;
	}
	public long getAvgWaitingTime(){
		return totalWaitingTime/totalOpened;
	}
	public int getPoolSize(){
		return pool.length;
	}
	public int getAvailableObjects(){
		return currentSize;
	}
	
	@Override
	public void close(){
		isClosing = 1;
		if(closeFunction == null)
			return;
		for(;currentSize > 0; currentSize--){
			IPoolEntry entry = open((byte)2);
			if(entry != null)
				closeFunction.accept(entry.value);
			else
				currentSize++;
		}
	}

	public void async(Consumer<IPoolEntry> action){
		Thread.ofVirtual().start(()->{
			IPoolEntry entry = null;
			try{
				entry = open();
				action.accept(entry);
			}catch(Exception e){
				action.accept(null);	
			}finally{
				entry.close();
			}
		});
	}
	public static <T> Builder<T> newBuilder(){
		return new Builder<T>();
	}

	public void asyncReset(Consumer<IPoolEntry> action){
		Thread.ofVirtual().start(()->{
			IPoolEntry entry = null;
			try{
				entry = open();
				entry.reset();
				action.accept(entry);
			}catch(Exception e){
				action.accept(null);
			}finally{
				entry.close();
			}
		});
	}

	public static class Builder<B> {
		private int size = 8;
		private int maxSize = 0;
		private Supplier<B> generator;
		private Function<B,B> resetFunction;
		private float scale = 1.5f;
		private Predicate<B> validator;
		private Function<B,B> reviver;
		private byte onInvalid = EXCEPTION;
		private int timeout = 0;
		private Consumer<B> closeFunction;
		private boolean autoReset = false;
		private byte fallback = RE_CREATE;
		private int maxRetry = 5;
		
		
		public Builder<B> size(int size) {
			if(size <= 0){
				throw new RuntimeException("Size must be positive");
			}
			this.size = size;
			return this;
		}
		public Builder<B> maxSize(int maxSize){
			if(maxSize < 0){
				throw new RuntimeException("Max size must be not negative");
			}
			this.maxSize = maxSize;
			return this;
		}
		public Builder<B> generator(Supplier<B> generator){
			this.generator = generator;
			return this;
		}
		public Builder<B> reset(Function<B,B> resetFunction){
			this.resetFunction = resetFunction;
			return this;
		}
		public Builder<B> scale(float scaleFactor){
			if(scaleFactor <= 1.0f){
				throw new RuntimeException("Scale factor must be greater than 1");
			}
			this.scale = scaleFactor;
			return this;
		}
		public Builder<B> validator(Predicate<B> validator){
			this.validator = validator;
			return this;
		}
		public Builder<B> onInvalid(byte action){
			if(action < 0 || action > 3){
				throw new RuntimeException("Invalid reaction can be only RESET = 0, RE_CREATE = 1, CUSTOM = 2, EXCEPTION = 3");
			}
			this.onInvalid = action;
			return this;
		}
		public Builder<B> reviver(Function<B,B> reviveFunction){
			this.reviver = reviveFunction;
			return this;
		}
		public Builder<B> autoReset(){
			this.autoReset = true;
			return this;
		}
		public Builder<B> destroy(Consumer<B> destroyFunction){
			this.closeFunction = destroyFunction;
			return this;
		}
		public Builder<B> maxRetry(int max){
			if(max < 0){
				throw new RuntimeException("Max retries must be not negative");
			}
			this.maxRetry = max;
			return this;
		}
		public Builder<B> fallback(byte action){
			if(action < 0 || action > 3 || action == 2){
				throw new RuntimeException("Fallback action can be only RESET = 0, RE_CREARE = 1, EXCEPTION = 3");
			}
			this.fallback = action;
			return this;
		}
		public Builder<B> timeout(int ms){
			if(ms < 0){
				throw new RuntimeException("Timeout must be not negative");
			}
			timeout = ms;
			return this;
		}
		public IPool<B> build(){
			if(generator == null){
				throw new RuntimeException("Generator must be set");
			}
			IPool<B> pool = new IPool<B>(size, generator);
			pool.maxSize = this.maxSize;
			pool.resetFunction = this.resetFunction;
			pool.reviver = this.reviver;
			pool.validator = this.validator;
			pool.scaleFactor = this.scale;
			pool.action = this.onInvalid;
			pool.timeout = this.timeout;
			pool.closeFunction = this.closeFunction;
			pool.autoReset = this.autoReset;
			pool.fallback = this.fallback;
			pool.maxRetry = this.maxRetry;
			return pool;
		}
	}
	
	public class IPoolEntry implements AutoCloseable{
		private IPoolEntry(T value){
			this.value = value;
		}
		public IPoolEntry reset(){
			if(resetFunction == null){
				throw new RuntimeException("Reset function not given");
			}
			value = resetFunction.apply(value);
			return this;
		}
		@Override
		public void close(){
			open = false;
			opened--;
			IPool.this.notifyAll();
		}
		private int retry = 0;
		private boolean invalid = false;
		private boolean open = true;
		public T value;
	}
}
