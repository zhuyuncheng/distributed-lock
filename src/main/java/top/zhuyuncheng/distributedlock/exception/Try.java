package top.zhuyuncheng.distributedlock.exception;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Get rid of Exception of lambda
 */
public class Try {

    public static <T, R> Function<T, R> of(UncheckedFunction<T, R> resolve, Consumer<Exception> reject) {
        return of(resolve, reject, null);
    }

    public static <T, R> Function<T, R> of(UncheckedFunction<T, R> resolve, Consumer<Exception> reject, R reassure) {
        Objects.requireNonNull(resolve);
        Objects.requireNonNull(reject);
        return t -> {
            try {
                return resolve.apply(t);
            } catch (Exception e) {
                reject.accept(e);

                return reassure;
            }
        };
    }

    @FunctionalInterface
    public interface UncheckedFunction<T, R> {
        R apply(T t) throws Exception;
    }
}