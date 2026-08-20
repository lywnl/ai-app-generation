package com.lyw.appgeneration.ai.image.model;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class ImageCollectionPlan implements Serializable {
    private List<ImageSearchTask> contentImageTasks;
    private List<IllustrationTask> illustrationTasks;
    private List<LogoTask> logoTasks;

    public record ImageSearchTask(String query) implements Serializable {}
    public record IllustrationTask(String query) implements Serializable {}
    public record LogoTask(String description) implements Serializable {}
}
