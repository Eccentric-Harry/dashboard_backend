import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
class TestLombok {
    @Builder.Default
    private String status = "TODO";

    public static void main(String[] args) {
        TestLombok t = new TestLombok();
        System.out.println("No-args constructor status: " + t.getStatus());
    }
}
