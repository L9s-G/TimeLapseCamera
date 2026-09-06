package com.timelapse.camera.ui.gallery

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.timelapse.camera.R
import com.timelapse.camera.databinding.FragmentGalleryBinding
import com.timelapse.camera.databinding.ItemPhotoBinding
import com.timelapse.camera.config.CaptureConfig
import com.timelapse.camera.storage.IPhotoStorage
import com.timelapse.camera.storage.PhotoStorageFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 相册页 Fragment —— 网格浏览历史照片。
 *
 * 功能：
 * - Grid 布局（3 列）展示照片
 * - 分页懒加载：首次加载前 30 张，滚动到底部自动加载更多
 * - Coil 加载缩略图，自动缓存和采样
 *
 * 教学要点：
 * - RecyclerView 的分页加载模式（offset 游标 + 底部监听）
 * - 内存友好：每次只加载一页（30张），避免一次性加载全部照片；
 *   注意：adapter.photos 存储的是 File 引用（每条目 ~50 字节），不是 Bitmap；
 *   实际 Bitmap 由 Coil 按 ImageView 尺寸（120dp）自动采样并管理缓存，
 *   Coil 内部有 LRU 回收，浏览深度对内存峰值影响有限。
 *   若要在离开页面时彻底释放 adapter 数据，可在 onDestroyView 中调用 adapter.setPhotos(emptyList())，
 *   本项目暂不强制，教学重点是理解分页加载模式。
 */
class GalleryFragment : Fragment() {

    private var _binding: FragmentGalleryBinding? = null
    private val binding get() = _binding!!

    private lateinit var storage: IPhotoStorage
    private val adapter = PhotoAdapter()

    /** 分页游标：已加载到 adapter 的照片总数 */
    private var loadedCount = 0
    private val PAGE_SIZE = 30

    /** 加载门闩：防止快速滚动时多个 loadNextPage 协程并发读同一 offset 导致重复追加 */
    @Volatile private var isLoading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storage = PhotoStorageFactory.create(requireContext(), CaptureConfig.load(requireContext()))
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGalleryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recyclerView.layoutManager = GridLayoutManager(context, 3)
        binding.recyclerView.adapter = adapter

        // 首次加载前 PAGE_SIZE 张
        loadNextPage()

        // 滚动到底部时加载更多
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return // 向上滚动不触发
                val lm = rv.layoutManager as? GridLayoutManager ?: return
                val lastPos = lm.findLastVisibleItemPosition()
                if (lastPos != RecyclerView.NO_POSITION && lastPos >= adapter.itemCount - 3) {
                    loadNextPage()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        // 存储位置可能变更，重新创建 storage 并重置分页状态
        storage = PhotoStorageFactory.create(requireContext(), CaptureConfig.load(requireContext()))
        loadedCount = 0
        adapter.setPhotos(emptyList())
        binding.tvEmpty.visibility = View.GONE
        loadNextPage()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * 加载下一页：从 loadedCount 开始取 PAGE_SIZE 张照片追加到 adapter。
     * 如果返回数量 < PAGE_SIZE 说明已到末尾。
     * isLoading 门闩保证同一时刻只有一个加载协程在跑。
     */
    private fun loadNextPage() {
        if (isLoading) return
        isLoading = true
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val photos = storage.getPhotosPaged(loadedCount, PAGE_SIZE)
                withContext(Dispatchers.Main) {
                    val b = _binding ?: return@withContext
                    if (photos.isEmpty()) {
                        // 无更多照片，显示空状态提示
                        if (adapter.itemCount == 0) b.tvEmpty.visibility = View.VISIBLE
                    } else {
                        adapter.addPhotos(photos)
                        loadedCount += photos.size
                        b.tvEmpty.visibility = View.GONE
                    }
                }
            } finally {
                isLoading = false
            }
        }
    }

    // ──────────── Adapter ────────────

    private inner class PhotoAdapter : RecyclerView.Adapter<PhotoViewHolder>() {

        private val photos = mutableListOf<File>()

        /** 追加照片（分页加载用），不触发全量重绘 */
        fun addPhotos(newPhotos: List<File>) {
            val start = photos.size
            photos.addAll(newPhotos)
            notifyItemRangeInserted(start, newPhotos.size)
        }

        /** 全量替换（用于重置） */
        fun setPhotos(newPhotos: List<File>) {
            photos.clear()
            photos.addAll(newPhotos)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
            val binding = ItemPhotoBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return PhotoViewHolder(binding)
        }

        override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
            holder.bind(photos[position])
        }

        override fun getItemCount(): Int = photos.size
    }

    private inner class PhotoViewHolder(
        private val binding: ItemPhotoBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(photo: File) {
            binding.ivPhoto.load(photo) {
                placeholder(android.R.color.darker_gray)
                crossfade(true)
            }
        }
    }

    companion object {
        private const val TAG = "GalleryFragment"
        fun newInstance() = GalleryFragment()
    }
}
