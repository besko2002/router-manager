package com.example.routermanager.api;

import com.example.routermanager.monitor.CounterSourceType;
import jakarta.persistence.*;

@Entity
@Table(name = "source_labels")
@IdClass(SourceLabel.Key.class)
public class SourceLabel {
    @Id @Enumerated(EnumType.STRING) @Column(name = "source_type", length = 16)
    private CounterSourceType sourceType;
    @Id @Column(name = "source_id", length = 32)
    private String sourceId;
    @Column(nullable = false, length = 60)
    private String label;

    protected SourceLabel() {}
    public SourceLabel(CounterSourceType sourceType, String sourceId, String label) {
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.label = label;
    }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public record Key(CounterSourceType sourceType, String sourceId) implements java.io.Serializable {}
}
