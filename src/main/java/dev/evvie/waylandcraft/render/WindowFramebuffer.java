package dev.evvie.waylandcraft.render;
import com.mojang.renderpearl.api.GpuFormat;
import java.util.Optional;
import net.minecraft.client.renderer.BindGroupLayouts;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;

import java.nio.ByteBuffer;
import java.util.ArrayList;

import org.joml.Matrix4fc;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.vertex.VertexFormat;

import dev.evvie.waylandcraft.WaylandCraftCommon;
import dev.evvie.waylandcraft.bridge.WLCSurface;
import dev.evvie.waylandcraft.bridge.WLCSurface.SurfaceDamage;
import dev.evvie.waylandcraft.bridge.WLCSurface.ViewportSource;
import dev.evvie.waylandcraft.displays.FramebufferRenderable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;

public class WindowFramebuffer implements FramebufferRenderable {
	
	private static final BindGroupLayout WINDOW_INFO_LAYOUT =
        	BindGroupLayout.builder()
                	.withUniform("WindowInfo", UniformType.UNIFORM_BUFFER)
                	.build();


	public static final RenderPipeline WINDOW_PIPELINE = RenderPipelines.register(
		RenderPipeline.builder()
		.withLocation(Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, "pipeline/window"))
		.withVertexShader(Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, "window"))
		.withFragmentShader(Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, "window"))
		.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
		.withBindGroupLayout(WINDOW_INFO_LAYOUT)
		.withColorTargetState(new ColorTargetState(new BlendFunction(BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_ALPHA)))
		.withCull(false)
		.build()
	);
	
	public static final RenderPipeline UNPREMULTIPLY_PIPELINE = RenderPipelines.register(
        RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, "pipeline/unpremultiply"))
                .withVertexShader("core/screenquad")
                .withFragmentShader(Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, "unpremultiply"))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
                .withColorTargetState(ColorTargetState.DEFAULT)
                .build()
		);
	
	public static final RenderPipeline DAMAGE_PIPELINE = RenderPipelines.register(
		RenderPipeline.builder()
		.withLocation(Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, "pipeline/damage"))
		.withVertexShader(Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, "window"))
		.withFragmentShader(Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, "window_damage"))
		.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
		
		.withBindGroupLayout(WINDOW_INFO_LAYOUT)
		.withColorTargetState(new ColorTargetState(new BlendFunction(BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_ALPHA)))
		.withCull(false)
		.build()
	);
	
	private static GpuBuffer alphaUniformBuffer = null;
        private static GpuBuffer opaqueUniformBuffer = null;
	private static boolean debugDamage = false;
	
	public final WLCSurface surfaceTree;
	private TextureTarget tempTarget = null;
	private TextureTarget target = null;
	private FramebufferTexture texture = null;
	private Identifier location = null;
	
	private int width = 0;
	private int height = 0;
	private int xoff;
	private int yoff;
	
	public WindowFramebuffer(WLCSurface surfaceTree) {
		this.surfaceTree = surfaceTree;
	}
	
        public static void endFrame() {
                /* 26.3: persistent uniform buffers, nothing to recycle */
        }
	
	private static void ensureUniformStorage() {
                if (alphaUniformBuffer == null) {
                        alphaUniformBuffer = RenderSystem.getDevice().createBuffer(
                                () -> "waylandcraft window alpha uniforms",
                                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                                WindowInfoUniform.SIZE
                        );

                        opaqueUniformBuffer = RenderSystem.getDevice().createBuffer(
                                () -> "waylandcraft window opaque uniforms",
                                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                                WindowInfoUniform.SIZE
                        );
                }
        }

        private static GpuBufferSlice writeWindowInfo(
                GpuBuffer buffer,
                WindowInfoUniform uniform
        ) {
                ByteBuffer data = ByteBuffer.allocateDirect(WindowInfoUniform.SIZE);
                uniform.write(data);
                data.flip();

                var encoder = RenderSystem.getDevice().createCommandEncoder();
                encoder.writeToBuffer(buffer.slice(), data);
                encoder.submit();

                return buffer.slice();
        }
	
	private void updateTarget() {
		int minX = 0;
		int minY = 0;
		int maxX = 0;
		int maxY = 0;
		
		for(WLCSurface surface = surfaceTree; surface != null; surface = surface.getNextChild()) {
			int sMinX = surface.xSubpos;
			int sMinY = surface.ySubpos;
			int sMaxX = sMinX + surface.width();
			int sMaxY = sMinY + surface.height();
			
			if(sMinX < minX) minX = sMinX;
			if(sMinY < minY) minY = sMinY;
			if(sMaxX > maxX) maxX = sMaxX;
			if(sMaxY > maxY) maxY = sMaxY;
		}
		
		int prevWidth = width;
		int prevHeight = height;
		
		this.xoff = -minX;
		this.yoff = -minY;
		this.width = maxX - minX;
		this.height = maxY - minY;
		
		if(width <= 0 || height <= 0) {
			destroy();
			return;
		}
		
		if(width != prevWidth || height != prevHeight) destroy();
		
		if(tempTarget == null) {
			tempTarget = new TextureTarget(name() + "-temp", width, height, GpuFormat.RGBA8_UNORM, null);
		}
		
		if(target == null) {
			target = new TextureTarget(name(), width, height, GpuFormat.RGBA8_UNORM, null);
		}
		
		if(texture == null) registerTexture();
	}
	
	private String name() {
		return "wayland-framebuffer-" + this.hashCode() + "-" + surfaceTree.hashCode();
	}
	
	public void render() {
		updateTarget();
		if(target == null || tempTarget == null) return;
		
		PoseStack poseStack = new PoseStack();
		poseStack.translate(-1.0, -1.0, 0.0);
		poseStack.scale(2.0f / width, 2.0f / height, 1.0f);
		
		ArrayList<CompiledBufferDraw> elements = new ArrayList<>();
		for(WLCSurface surface = surfaceTree; surface != null; surface = surface.getNextChild()) {
			BufferDraw draw = bakeSurface(surface, xoff + surface.xSubpos, yoff + surface.ySubpos);
			if(draw != null) elements.add(draw.compile());
		}
		
		ensureUniformStorage();
		GpuBufferSlice alphaUniforms = writeWindowInfo(alphaUniformBuffer, new WindowInfoUniform(poseStack.last().pose(), true));
		GpuBufferSlice opaqueUniforms = writeWindowInfo(opaqueUniformBuffer, new WindowInfoUniform(poseStack.last().pose(), false));
		
		try {
			try(RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "window framebuffer", tempTarget.getColorTextureView(), Optional.empty())) {
				pass.setPipeline(RenderSystem.getCompiledPipeline(WINDOW_PIPELINE));
				for(CompiledBufferDraw element : elements) {
					pass.setUniform("WindowInfo", element.alpha ? alphaUniforms : opaqueUniforms);
					pass.setUniform("Sampler0", element.textureView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
					pass.setVertexBuffer(0, element.vertexBuffer.slice());
					pass.setIndexBuffer(element.indexBuffer, element.indexType);
					pass.drawIndexed(0, 0, element.indexCount, 1, 0);
				}
			}
		}
		finally {
			for(CompiledBufferDraw element : elements) {
				element.vertexBuffer.close();
			}
		}
		
		if(debugDamage) drawDebugDamage(opaqueUniforms);
		
		try(RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "window framebuffer unpremultiply", target.getColorTextureView(), Optional.empty())) {
			pass.setPipeline(RenderSystem.getCompiledPipeline(UNPREMULTIPLY_PIPELINE));
			pass.setUniform("Sampler0", tempTarget.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.draw(0, 3, 0, 1);
		}
	}
	
	private void drawDebugDamage(GpuBufferSlice opaqueUniforms) {
		ArrayList<CompiledBufferDraw> damageElements = new ArrayList<>();
		for(WLCSurface surface = surfaceTree; surface != null; surface = surface.getNextChild()) {
			int sx = xoff + surface.xSubpos;
			int sy = yoff + surface.ySubpos;
			
			for(SurfaceDamage damage : surface.getDamage()) {
				damageElements.add(new BufferDraw(null, sx + damage.x(), sy + damage.y(), damage.width(), damage.height(), 0, 0, 0, 0, false).compile());
			}
		}
		
		try {
			try(RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "window framebuffer damage", tempTarget.getColorTextureView(), Optional.empty())) {
				pass.setPipeline(RenderSystem.getCompiledPipeline(DAMAGE_PIPELINE));
				pass.setUniform("WindowInfo", opaqueUniforms);
				for(CompiledBufferDraw element : damageElements) {
					pass.setVertexBuffer(0, element.vertexBuffer.slice());
					pass.setIndexBuffer(element.indexBuffer, element.indexType);
					pass.drawIndexed(0, 0, element.indexCount, 1, 0);
				}
			}
		}
		finally {
			for(CompiledBufferDraw element : damageElements) {
				element.vertexBuffer.close();
			}
		}
	}
	
	private BufferDraw bakeSurface(WLCSurface surface, float x, float y) {
		BufferTexture buf = surface.getBuffer();
		if(buf == null) return null;
		
		float w = surface.width();
		float h = surface.height();
		
		float crop_x1 = 0.0f;
		float crop_y1 = 0.0f;
		float crop_x2 = 1.0f;
		float crop_y2 = 1.0f;
		
		ViewportSource src = surface.getViewportSource();
		if(src != null) {
			crop_x1 = (float) (src.x() / buf.width);
			crop_y1 = (float) (src.y() / buf.height);
			crop_x2 = (float) ((src.x() + src.width()) / buf.width);
			crop_y2 = (float) ((src.y() + src.height()) / buf.height);
		}
		
		return new BufferDraw(buf.getTextureView(), x, y, w, h, crop_x1, crop_y1, crop_x2, crop_y2, buf.format != BufferTexture.FORMAT_XRGB8888);
	}
	
	private static record CompiledBufferDraw(GpuTextureView textureView, GpuBuffer vertexBuffer, GpuBuffer indexBuffer, int indexCount, IndexType indexType, boolean alpha) {
	}
	
	private static record BufferDraw(GpuTextureView textureView, float x, float y, float w, float h, float u1, float v1, float u2, float v2, boolean alpha) {
		
		public CompiledBufferDraw compile() {
			try(ByteBufferBuilder byteBuilder = new ByteBufferBuilder(DefaultVertexFormat.POSITION_TEX.getVertexSize() * 4)) {
				BufferBuilder builder = new BufferBuilder(byteBuilder, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX);
				builder.addVertex(x, y, 0).setUv(u1, v1);
				builder.addVertex(x + w, y, 0).setUv(u2, v1);
				builder.addVertex(x + w, y + h, 0).setUv(u2, v2);
				builder.addVertex(x, y + h, 0).setUv(u1, v2);
				
				try(MeshData mesh = builder.buildOrThrow()) {
					int indexCount = mesh.drawState().indexCount();
					RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
					GpuBuffer vertexBuffer = RenderSystem.getDevice().createBuffer(null, GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, mesh.vertexBuffer());
					GpuBuffer indexBuffer = indices.getBuffer(indexCount);
					return new CompiledBufferDraw(textureView, vertexBuffer, indexBuffer, indexCount, indices.type(), alpha);
				}
			}
		}
		
	}
	
	private void registerTexture() {
		if(target == null) return;
		
		texture = new FramebufferTexture(getTextureView());
		location = Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, name());
		
		Minecraft.getInstance().getTextureManager().register(location, texture);
	}
	
	private void unregisterTexture() {
		TextureManager manager = Minecraft.getInstance().getTextureManager();
		manager.register(location, manager.getTexture(MissingTextureAtlasSprite.getLocation()));
		texture = null;
		location = null;
	}
	
	public void destroy() {
		if(target != null) target.destroyBuffers();
		if(tempTarget != null) tempTarget.destroyBuffers();
		if(texture != null) unregisterTexture();
		target = null;
		tempTarget = null;
	}
	
	@Override
	public int getWidth() {
		return width;
	}
	
	@Override
	public int getHeight() {
		return height;
	}
	
	@Override
	public int getXOff() {
		return xoff;
	}
	
	@Override
	public int getYOff() {
		return yoff;
	}
	
	public GpuTextureView getTextureView() {
		if(target == null) return null;
		return target.getColorTextureView();
	}
	
	public Identifier getTextureLocation() {
		return location;
	}
	
	public boolean isValid() {
		return target != null;
	}
	
	private static class FramebufferTexture extends AbstractTexture {
		
		public FramebufferTexture(GpuTextureView textureView) {
			this.textureView = textureView;
			this.texture = textureView.texture();
			this.sampler = RenderUtils.WINDOW_SAMPLER.get();
		}
		
		@Override
		public void close() {
		}
		
	}
	
	private static record WindowInfoUniform(Matrix4fc mat, boolean alpha) {
		
		public static final int SIZE = new Std140SizeCalculator().putMat4f().putFloat().get();
		
		public void write(ByteBuffer byteBuffer) {
			Std140Builder.intoBuffer(byteBuffer).putMat4f(mat).putFloat(alpha ? 0.0f : 1.0f);
		}
		
	}
	
}
